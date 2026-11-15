//! DMA-BUF allocation for the compositor's own capture protocol.
//!
//! `zwlr_screencopy` and `ext_image_copy_capture` both copy into a buffer the
//! client provides. A shared-memory buffer costs a full-frame copy through the
//! CPU; a DMA-BUF buffer lets the compositor draw straight into memory the GPU
//! already owns, which only pays off when the pixels stay on the GPU (see the
//! measurement in `wlr_screencopy`).
//!
//! libgbm is loaded at run time and a render node is opened lazily, so a system
//! without GBM, without a render node, or with a driver that cannot allocate the
//! requested format simply reports the error and the caller keeps its
//! shared-memory path.
use anyhow::{Context, Result, bail, ensure};
use libloading::Library;
use std::ffi::c_void;
use std::os::fd::{AsFd, AsRawFd, BorrowedFd, FromRawFd, OwnedFd};

/// `DRM_FORMAT_XRGB8888` and `DRM_FORMAT_ARGB8888` as the compositor names them.
pub(super) const DRM_FORMAT_XRGB8888: u32 = 0x3432_5258;
pub(super) const DRM_FORMAT_ARGB8888: u32 = 0x3432_5241;

/// `DRM_FORMAT_MOD_LINEAR`, the one modifier every driver can allocate.
pub(super) const DRM_FORMAT_MOD_LINEAR: u64 = 0;

type GbmCreateDevice = unsafe extern "C" fn(i32) -> *mut c_void;
type GbmDeviceDestroy = unsafe extern "C" fn(*mut c_void);
type GbmBoCreateWithModifiers =
    unsafe extern "C" fn(*mut c_void, u32, u32, u32, *const u64, u32) -> *mut c_void;
type GbmBoDestroy = unsafe extern "C" fn(*mut c_void);
type GbmBoGetFd = unsafe extern "C" fn(*mut c_void) -> i32;
type GbmBoGetStride = unsafe extern "C" fn(*mut c_void) -> u32;
type GbmBoGetOffset = unsafe extern "C" fn(*mut c_void, i32) -> u32;
type GbmBoGetModifier = unsafe extern "C" fn(*mut c_void) -> u64;

/// A GBM device over one render node.
pub(super) struct Device {
    // Dropped after the device itself, hence the explicit destroy below.
    _library: Library,
    handle: *mut c_void,
    _fd: OwnedFd,
    destroy: GbmDeviceDestroy,
    create_with_modifiers: GbmBoCreateWithModifiers,
    bo_destroy: GbmBoDestroy,
    bo_get_fd: GbmBoGetFd,
    bo_get_stride: GbmBoGetStride,
    bo_get_offset: GbmBoGetOffset,
    bo_get_modifier: GbmBoGetModifier,
}

impl Device {
    /// Opens the first render node that yields a GBM device, or the one whose
    /// PCI address matches `pci_bus` when a CUDA device has to import the
    /// buffer it allocates: a dma-buf can only be imported by the GPU that
    /// owns it.
    pub(super) fn open(pci_bus: Option<&str>) -> Result<Self> {
        let library = unsafe {
            Library::new("libgbm.so.1")
                .or_else(|_| Library::new("libgbm.so"))
                .context("没有 libgbm")
        }?;
        // Safe because each symbol is looked up by the signature libgbm exports.
        unsafe {
            let create: GbmCreateDevice = *library
                .get(b"gbm_create_device\0")
                .context("libgbm 缺少 gbm_create_device")?;
            let destroy: GbmDeviceDestroy = *library
                .get(b"gbm_device_destroy\0")
                .context("libgbm 缺少 gbm_device_destroy")?;
            let create_with_modifiers: GbmBoCreateWithModifiers = *library
                .get(b"gbm_bo_create_with_modifiers\0")
                .context("libgbm 缺少 gbm_bo_create_with_modifiers")?;
            let bo_destroy: GbmBoDestroy = *library
                .get(b"gbm_bo_destroy\0")
                .context("libgbm 缺少 gbm_bo_destroy")?;
            let bo_get_fd: GbmBoGetFd = *library
                .get(b"gbm_bo_get_fd\0")
                .context("libgbm 缺少 gbm_bo_get_fd")?;
            let bo_get_stride: GbmBoGetStride = *library
                .get(b"gbm_bo_get_stride\0")
                .context("libgbm 缺少 gbm_bo_get_stride")?;
            let bo_get_offset: GbmBoGetOffset = *library
                .get(b"gbm_bo_get_offset\0")
                .context("libgbm 缺少 gbm_bo_get_offset")?;
            let bo_get_modifier: GbmBoGetModifier = *library
                .get(b"gbm_bo_get_modifier\0")
                .context("libgbm 缺少 gbm_bo_get_modifier")?;
            let mut last = None;
            for index in 128..=135u32 {
                if let Some(wanted) = pci_bus
                    && !same_device(index, wanted)
                {
                    continue;
                }
                let path = format!("/dev/dri/renderD{index}");
                let fd = match std::fs::OpenOptions::new()
                    .read(true)
                    .write(true)
                    .open(&path)
                {
                    Ok(file) => file,
                    Err(error) => {
                        last = Some(format!("{path}: {error}"));
                        continue;
                    }
                };
                let owned = OwnedFd::from(fd);
                let handle = create(owned.as_raw_fd());
                if handle.is_null() {
                    last = Some(format!("{path}: gbm_create_device 失败"));
                    continue;
                }
                return Ok(Self {
                    _library: library,
                    handle,
                    _fd: owned,
                    destroy,
                    create_with_modifiers,
                    bo_destroy,
                    bo_get_fd,
                    bo_get_stride,
                    bo_get_offset,
                    bo_get_modifier,
                });
            }
            bail!(
                "没有可用的 DRM render node（{}）",
                last.unwrap_or_else(|| "未找到 /dev/dri/renderD*".into())
            )
        }
    }

    /// Allocates one single-plane buffer in `format`, restricted to the
    /// modifiers the compositor said it can import.
    pub(super) fn allocate(
        &self,
        width: u32,
        height: u32,
        format: u32,
        modifiers: &[u64],
    ) -> Result<Buffer> {
        ensure!(width > 0 && height > 0, "DMA-BUF 尺寸无效");
        ensure!(!modifiers.is_empty(), "没有可用的 modifier");
        // Safe because the device handle is live and the slice is valid for the
        // duration of the call.
        let bo = unsafe {
            (self.create_with_modifiers)(
                self.handle,
                width,
                height,
                format,
                modifiers.as_ptr(),
                u32::try_from(modifiers.len()).unwrap_or(u32::MAX),
            )
        };
        if bo.is_null() {
            bail!("无法分配 {width}x{height} 的 DMA-BUF");
        }
        // Safe because `bo` was just created by this device.
        let fd = unsafe { (self.bo_get_fd)(bo) };
        if fd < 0 {
            unsafe { (self.bo_destroy)(bo) };
            bail!("无法导出 DMA-BUF 的文件描述符");
        }
        // Safe because `fd` is a fresh descriptor this call owns.
        let fd = unsafe { OwnedFd::from_raw_fd(fd) };
        // Safe because `bo` is live.
        let (stride, offset, modifier) = unsafe {
            (
                (self.bo_get_stride)(bo),
                (self.bo_get_offset)(bo, 0),
                (self.bo_get_modifier)(bo),
            )
        };
        ensure!(stride >= width.saturating_mul(4), "DMA-BUF 行距小于一行");
        Ok(Buffer {
            bo,
            destroy: self.bo_destroy,
            fd,
            width,
            height,
            format,
            stride,
            offset,
            modifier,
        })
    }
}

/// Whether a render node belongs to the PCI device `pci_bus` names.
///
/// CUDA reports `domain:bus:device.function` with a full 32-bit domain, sysfs
/// names the same device with a 16-bit one, so only the trailing
/// `bus:device.function` is compared.
fn same_device(index: u32, pci_bus: &str) -> bool {
    let Ok(node) = std::fs::read_link(format!("/sys/class/drm/renderD{index}/device")) else {
        return false;
    };
    let Some(name) = node
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
    else {
        return false;
    };
    let tail = |value: &str| {
        value
            .get(value.len().saturating_sub(7)..)
            .unwrap_or(value)
            .to_string()
    };
    tail(&name) == tail(pci_bus)
}

impl Drop for Device {
    fn drop(&mut self) {
        // Safe because the device handle is still owned here.
        unsafe { (self.destroy)(self.handle) };
    }
}

/// One allocated buffer. Dropping it releases the GPU memory; the compositor
/// keeps its own reference while a frame is in flight.
pub(super) struct Buffer {
    bo: *mut c_void,
    destroy: GbmBoDestroy,
    fd: OwnedFd,
    width: u32,
    height: u32,
    format: u32,
    stride: u32,
    offset: u32,
    modifier: u64,
}

impl Buffer {
    pub(super) fn fd(&self) -> BorrowedFd<'_> {
        self.fd.as_fd()
    }
    pub(super) fn raw_fd(&self) -> i32 {
        self.fd.as_raw_fd()
    }
    pub(super) fn width(&self) -> u32 {
        self.width
    }
    pub(super) fn height(&self) -> u32 {
        self.height
    }
    pub(super) fn format(&self) -> u32 {
        self.format
    }
    pub(super) fn stride(&self) -> u32 {
        self.stride
    }
    pub(super) fn offset(&self) -> u32 {
        self.offset
    }
    pub(super) fn modifier(&self) -> u64 {
        self.modifier
    }
}

impl Drop for Buffer {
    fn drop(&mut self) {
        // Safe because the buffer object is owned here and dropped once.
        unsafe { (self.destroy)(self.bo) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore = "needs a DRM render node"]
    fn a_buffer_exports_a_dmabuf() {
        let device = Device::open(None).expect("gbm device");
        let buffer = device
            .allocate(64, 64, DRM_FORMAT_XRGB8888, &[DRM_FORMAT_MOD_LINEAR])
            .expect("dmabuf");
        assert_eq!((buffer.width(), buffer.height()), (64, 64));
        assert_eq!(buffer.format(), DRM_FORMAT_XRGB8888);
        assert!(buffer.stride() >= 64 * 4);
        assert!(buffer.raw_fd() >= 0);
    }

    #[test]
    #[ignore = "needs a DRM render node and a CUDA device"]
    fn a_dmabuf_imports_into_cuda() {
        let context = crate::platform::linux::cuda::Context::shared(0).expect("cuda context");
        let device = Device::open(Some(context.pci_bus())).expect("gbm device");
        let (width, height) = (256u32, 128u32);
        let buffer = device
            .allocate(width, height, DRM_FORMAT_XRGB8888, &[DRM_FORMAT_MOD_LINEAR])
            .expect("dmabuf");
        let imported = crate::platform::linux::cuda::Buffer::import(
            &context,
            buffer.raw_fd(),
            u64::from(buffer.stride()) * u64::from(height),
            width,
            height,
            buffer.stride() as usize,
        )
        .expect("import");
        assert!(imported.ptr != 0);
        assert!(imported.pitch >= width as usize * 4);
    }
}
