//! Minimal OpenXR 1.0 loader ABI. Run ONLY in the dedicated diagnostic child process.
use super::runtime::Probe;
use anyhow::Result;
use std::ffi::{c_char, c_void};
#[repr(C)]
struct ApplicationInfo {
    name: [u8; 128],
    version: u32,
    engine: [u8; 128],
    engine_version: u32,
    api: u64,
}
#[repr(C)]
struct CreateInfo {
    ty: i32,
    next: *const c_void,
    flags: u64,
    app: ApplicationInfo,
    layers: u32,
    layer_names: *const *const c_char,
    extensions: u32,
    extension_names: *const *const c_char,
}
#[repr(C)]
struct SystemInfo {
    ty: i32,
    next: *const c_void,
    form: i32,
}
/// Loading the loader/runtime is isolated from GUI and host by `pico-vr runtime-probe`.
pub fn probe() -> Result<Probe> {
    let mut create = CreateInfo {
        ty: 3,
        next: std::ptr::null(),
        flags: 0,
        app: ApplicationInfo {
            name: [0; 128],
            version: 1,
            engine: [0; 128],
            engine_version: 0,
            api: 1u64 << 48,
        },
        layers: 0,
        layer_names: std::ptr::null(),
        extensions: 0,
        extension_names: std::ptr::null(),
    };
    let name = b"OpenUUYC runtime probe";
    create.app.name[..name.len()].copy_from_slice(name);
    let info = SystemInfo {
        ty: 4,
        next: std::ptr::null(),
        form: 1,
    };
    let mut instance = std::ptr::null_mut::<c_void>();
    let mut system = 0u64;
    // SAFETY: pinned OpenXR 1.0 C layouts, ABI sizes checked below; library
    // remains live until the instance has been destroyed. No borrowed callbacks.
    unsafe {
        let loader = libloading::Library::new("libopenxr_loader.so.1")?;
        let initialize = loader
            .get::<unsafe extern "system" fn(*const CreateInfo, *mut *mut c_void) -> i32>(
                b"xrCreateInstance\0",
            )?;
        let get_system =
            loader
                .get::<unsafe extern "system" fn(*mut c_void, *const SystemInfo, *mut u64) -> i32>(
                    b"xrGetSystem\0",
                )?;
        let destroy =
            loader.get::<unsafe extern "system" fn(*mut c_void) -> i32>(b"xrDestroyInstance\0")?;
        let initialized = initialize(&create, &mut instance);
        let available = initialized >= 0 && !instance.is_null();
        let result = if available {
            let result = get_system(instance, &info, &mut system);
            destroy(instance);
            result
        } else {
            -6
        };
        Ok(Probe {
            version: 1,
            instance: available,
            system: result >= 0 && system != 0,
            instance_result: initialized,
            system_result: result,
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn linux_64_bit_abi_matches_khronos_headers() {
        assert_eq!(std::mem::size_of::<ApplicationInfo>(), 272);
        assert_eq!(std::mem::size_of::<CreateInfo>(), 328);
        assert_eq!(std::mem::size_of::<SystemInfo>(), 24);
        assert_eq!(std::mem::offset_of!(CreateInfo, app), 24);
        assert_eq!(std::mem::offset_of!(CreateInfo, extension_names), 320);
    }
}
