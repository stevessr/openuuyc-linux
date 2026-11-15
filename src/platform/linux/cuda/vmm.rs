//! CUDA VMM allocations with an explicitly checked DMA-BUF export.
//! POSIX shareable CUDA handles are opaque handles, not Wayland DMA-BUFs.
use super::{Context, DevicePtr, check};
use anyhow::{Context as _, Result, ensure};
use std::{
    ffi::c_void,
    os::fd::{FromRawFd, OwnedFd},
    sync::Arc,
};

#[repr(C)]
#[derive(Clone, Copy)]
struct Location {
    kind: i32,
    id: i32,
}
#[repr(C)]
struct Properties {
    kind: i32,
    handles: i32,
    location: Location,
    win32_metadata: *mut c_void,
    flags: [u8; 8],
}
#[repr(C)]
struct Access {
    location: Location,
    flags: i32,
}

struct Api {
    granularity: unsafe extern "C" fn(*mut usize, *const Properties, i32) -> i32,
    create: unsafe extern "C" fn(*mut u64, usize, *const Properties, u64) -> i32,
    reserve: unsafe extern "C" fn(*mut DevicePtr, usize, usize, DevicePtr, u64) -> i32,
    map: unsafe extern "C" fn(DevicePtr, usize, usize, u64, u64) -> i32,
    access: unsafe extern "C" fn(DevicePtr, usize, *const Access, usize) -> i32,
    unmap: unsafe extern "C" fn(DevicePtr, usize) -> i32,
    free_address: unsafe extern "C" fn(DevicePtr, usize) -> i32,
    release: unsafe extern "C" fn(u64) -> i32,
    export: unsafe extern "C" fn(*mut c_void, DevicePtr, usize, i32, u64) -> i32,
    attribute: unsafe extern "C" fn(*mut i32, i32, i32) -> i32,
}
impl Api {
    fn load(context: &Context) -> Result<Self> {
        // Optional VMM symbols: a driver without them keeps pitched allocation.
        unsafe {
            let library = &context.api._library;
            Ok(Self {
                granularity: *library.get(b"cuMemGetAllocationGranularity\0")?,
                create: *library.get(b"cuMemCreate\0")?,
                reserve: *library.get(b"cuMemAddressReserve\0")?,
                map: *library.get(b"cuMemMap\0")?,
                access: *library.get(b"cuMemSetAccess\0")?,
                unmap: *library.get(b"cuMemUnmap\0")?,
                free_address: *library.get(b"cuMemAddressFree\0")?,
                release: *library.get(b"cuMemRelease\0")?,
                export: *library.get(b"cuMemGetHandleForAddressRange\0")?,
                attribute: *library.get(b"cuDeviceGetAttribute\0")?,
            })
        }
    }
}

/// A linear BGRA device allocation. VMM mapping and physical allocation live
/// until this object drops; an exported fd must not outlive its owner.
pub(crate) struct Allocation {
    context: Arc<Context>,
    api: Api,
    handle: Option<u64>,
    reserved: bool,
    mapped: bool,
    pub ptr: DevicePtr,
    pub pitch: usize,
    size: usize,
}

impl Allocation {
    pub fn new(context: &Arc<Context>, width: u32, height: u32) -> Result<Self> {
        ensure!(width > 0 && height > 0, "VMM dimensions must be nonzero");
        let pitch = (width as usize)
            .checked_mul(4)
            .context("VMM row overflow")?
            .checked_add(255)
            .context("VMM pitch overflow")?
            & !255;
        let bytes = pitch
            .checked_mul(height as usize)
            .context("VMM size overflow")?;
        let api = Api::load(context)?;
        let _current = context.enter()?;
        let prop = Properties {
            kind: 1,    // CU_MEM_ALLOCATION_TYPE_PINNED
            handles: 0, // DMA-BUF export does not require opaque POSIX handles.
            location: Location {
                kind: 1,
                id: context.device,
            },
            win32_metadata: std::ptr::null_mut(),
            flags: [0; 8],
        };
        let mut granularity = 0;
        check(
            context.api,
            unsafe { (api.granularity)(&mut granularity, &prop, 0) },
            "cuMemGetAllocationGranularity",
        )?;
        ensure!(granularity.is_power_of_two(), "invalid VMM granularity");
        let size = bytes
            .checked_add(granularity - 1)
            .context("VMM allocation overflow")?
            & !(granularity - 1);
        let mut allocation = Self {
            context: context.clone(),
            api,
            handle: None,
            reserved: false,
            mapped: false,
            ptr: 0,
            pitch,
            size,
        };
        let mut handle = 0;
        check(
            context.api,
            unsafe { (allocation.api.create)(&mut handle, size, &prop, 0) },
            "cuMemCreate",
        )?;
        allocation.handle = Some(handle);
        check(
            context.api,
            unsafe { (allocation.api.reserve)(&mut allocation.ptr, size, granularity, 0, 0) },
            "cuMemAddressReserve",
        )?;
        allocation.reserved = true;
        check(
            context.api,
            unsafe { (allocation.api.map)(allocation.ptr, size, 0, handle, 0) },
            "cuMemMap",
        )?;
        allocation.mapped = true;
        let access = Access {
            location: prop.location,
            flags: 3,
        };
        check(
            context.api,
            unsafe { (allocation.api.access)(allocation.ptr, size, &access, 1) },
            "cuMemSetAccess",
        )?;
        Ok(allocation)
    }

    pub fn dmabuf_supported(context: &Context) -> Result<bool> {
        let api = Api::load(context)?;
        let mut supported = 0;
        check(
            context.api,
            unsafe { (api.attribute)(&mut supported, 124, context.device) },
            "cuDeviceGetAttribute(DMA_BUF_SUPPORTED)",
        )?;
        Ok(supported != 0)
    }

    /// Exports a real DMA-BUF, never an opaque POSIX CUDA sharing handle.
    pub fn export_dmabuf(&self) -> Result<OwnedFd> {
        ensure!(
            Self::dmabuf_supported(&self.context)?,
            "CUDA device reports DMA_BUF_SUPPORTED=0; VMM cannot enable DMA-BUF export"
        );
        let _current = self.context.enter()?;
        let mut fd = -1;
        check(
            self.context.api,
            unsafe { (self.api.export)((&mut fd as *mut i32).cast(), self.ptr, self.size, 1, 0) },
            "cuMemGetHandleForAddressRange(VMM DMA-BUF)",
        )?;
        ensure!(fd >= 0, "CUDA did not export a descriptor");
        Ok(unsafe { OwnedFd::from_raw_fd(fd) })
    }
}

impl Drop for Allocation {
    fn drop(&mut self) {
        let _current = match self.context.enter() {
            Ok(current) => current,
            Err(error) => {
                tracing::warn!(%error, "VMM cleanup cannot enter context; resources leaked");
                return;
            }
        };
        unsafe {
            if self.mapped {
                let status = (self.api.unmap)(self.ptr, self.size);
                if status != 0 {
                    // Conservatively leak rather than release backing while a
                    // driver mapping may still address it. Drop cannot retry.
                    tracing::warn!(status, "VMM unmap failed; resources leaked");
                    return;
                }
            }
            if self.reserved {
                let status = (self.api.free_address)(self.ptr, self.size);
                if status != 0 {
                    // The mapping was removed already, so the physical handle
                    // can still be released even if the VA reservation leaks.
                    tracing::warn!(status, "VMM address reservation could not be released");
                }
            }
            if let Some(handle) = self.handle {
                let status = (self.api.release)(handle);
                if status != 0 {
                    tracing::warn!(status, "VMM physical allocation could not be released");
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vmm_struct_layout_matches_cuda_headers() {
        assert_eq!(std::mem::size_of::<Properties>(), 32);
        assert_eq!(std::mem::offset_of!(Properties, win32_metadata), 16);
        assert_eq!(std::mem::size_of::<Access>(), 12);
    }

    #[test]
    #[ignore = "requires a CUDA device; verifies fallback when DMA-BUF is unavailable"]
    fn live_vmm_allocation_and_export_capability() {
        let context = Context::shared(0).expect("CUDA context");
        let supported = Allocation::dmabuf_supported(&context).expect("DMA-BUF capability");
        for _ in 0..8 {
            let allocation = Allocation::new(&context, 256, 128).expect("VMM allocation");
            assert_ne!(allocation.ptr, 0);
            assert!(allocation.pitch >= 256 * 4);
            let source: Vec<u8> = (0..256 * 128 * 4).map(|i| (i % 251) as u8).collect();
            let mut destination = vec![0; source.len()];
            let current = context.enter().expect("current context");
            let upload = super::super::Memcpy2d {
                src_memory_type: super::super::MEMORY_HOST,
                src_host: source.as_ptr().cast(),
                src_pitch: 256 * 4,
                dst_memory_type: super::super::MEMORY_DEVICE,
                dst_device: allocation.ptr,
                dst_pitch: allocation.pitch,
                width_in_bytes: 256 * 4,
                height: 128,
                ..Default::default()
            };
            check(
                context.api,
                unsafe { (context.api.memcpy_2d)(&upload) },
                "VMM upload",
            )
            .unwrap();
            let download = super::super::Memcpy2d {
                src_memory_type: super::super::MEMORY_DEVICE,
                src_device: allocation.ptr,
                src_pitch: allocation.pitch,
                dst_memory_type: super::super::MEMORY_HOST,
                dst_host: destination.as_mut_ptr().cast(),
                dst_pitch: 256 * 4,
                width_in_bytes: 256 * 4,
                height: 128,
                ..Default::default()
            };
            check(
                context.api,
                unsafe { (context.api.memcpy_2d)(&download) },
                "VMM download",
            )
            .unwrap();
            current.synchronize().expect("VMM synchronization");
            assert_eq!(source, destination);
            let exported = allocation.export_dmabuf();
            if supported {
                exported.expect("DMA-BUF export");
            } else {
                assert!(
                    exported
                        .unwrap_err()
                        .to_string()
                        .contains("DMA_BUF_SUPPORTED=0")
                );
            }
        }
        println!("VMM allocation/map/access/release: PASS; DMA_BUF_SUPPORTED={supported}");
    }
}
