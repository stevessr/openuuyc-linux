fn main() {
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    let windows = target_os == "windows";
    assert!(
        windows || target_os == "linux",
        "OpenUUYC supports Windows and Linux; {target_os} has no platform backend"
    );
    if windows {
        println!("cargo:rerun-if-changed=assets/windows.rc");
        println!("cargo:rerun-if-changed=assets/windows.manifest");
        println!("cargo:rerun-if-changed=assets/icon.ico");
        embed_resource::compile_for("assets/windows.rc", ["OpenUUYC"], embed_resource::NONE)
            .manifest_required()
            .expect("compile Windows application icon");
    }
    if target_os == "linux" {
        build_pcvr_driver();
        build_pcvr_mirror();
    }
    build_neteq(windows);
    build_fonts();
    // The virtual audio device is a Windows kernel driver package.
    if windows {
        bundle_audio_driver();
    }
}

fn bundle_audio_driver() {
    use std::path::PathBuf;
    println!("cargo:rerun-if-env-changed=OPENUUYC_AUDIO_DRIVER_DIR");
    println!("cargo:rerun-if-changed=drivers/audio/OpenUUYCAudio.inf");
    let output = PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    let directory = std::env::var_os("OPENUUYC_AUDIO_DRIVER_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("assets/drivers/audio"));
    // Match build.ps1's localized UTF-16LE package, including its BOM.
    let inf_source = std::fs::read_to_string("drivers/audio/OpenUUYCAudio.inf").unwrap();
    let inf: Vec<u8> = std::iter::once(0xfeffu16)
        .chain(inf_source.encode_utf16())
        .flat_map(u16::to_le_bytes)
        .collect();
    let mut generated = String::from("pub(super) const FILES: &[(&str, &[u8])] = &[\n");
    for extension in ["inf", "sys", "cat"] {
        let name = format!("OpenUUYCAudio.{extension}");
        let path = directory.join(&name);
        println!("cargo:rerun-if-changed={}", path.display());
        let bytes = std::fs::read(&path).expect("read bundled audio driver package");
        assert!(
            !bytes.is_empty(),
            "audio driver package contains an empty file"
        );
        if extension == "inf" {
            assert_eq!(bytes, inf, "audio driver INF differs from source");
        }
        std::fs::write(output.join(&name), bytes).unwrap();
        generated.push_str(&format!(
            "(\"{name}\", include_bytes!(concat!(env!(\"OUT_DIR\"), \"/{name}\"))),\n"
        ));
    }
    generated.push_str("];\n");
    std::fs::write(output.join("audio_driver.rs"), generated).unwrap();
}

fn build_fonts() {
    use std::io::Write;
    let directory = std::path::PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    for (name, bytes) in [
        ("Hack", epaint_default_fonts::HACK_REGULAR),
        (
            "NotoEmoji-Regular",
            epaint_default_fonts::NOTO_EMOJI_REGULAR,
        ),
        ("Ubuntu-Light", epaint_default_fonts::UBUNTU_LIGHT),
        ("emoji-icon-font", epaint_default_fonts::EMOJI_ICON),
    ] {
        let mut encoder = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::best());
        encoder.write_all(bytes).expect("compress bundled font");
        std::fs::write(
            directory.join(format!("{name}.zlib")),
            encoder.finish().unwrap(),
        )
        .expect("write bundled font");
    }
}

fn build_neteq(windows: bool) {
    let root = std::path::Path::new("vendor/webrtc_neteq");
    println!("cargo:rerun-if-changed=vendor/webrtc_neteq");
    println!("cargo:rerun-if-changed=src/media/audio/neteq_bridge.cc");
    let sources = std::fs::read_to_string(root.join("sources.txt")).expect("NetEq source manifest");
    let mut cpp = cc::Build::new();
    let mut c = cc::Build::new();
    for build in [&mut cpp, &mut c] {
        build
            .opt_level(3)
            .include(root)
            .define("NDEBUG", None)
            .define("RTC_DISABLE_LOGGING", None)
            .define("RTC_DISABLE_TRACE_EVENTS", None)
            .define("WEBRTC_APM_DEBUG_DUMP", "0")
            .define("WEBRTC_OPUS_SUPPORT_120MS_PTIME", "1")
            .warnings(false);
        if windows {
            build
                .define("WEBRTC_WIN", None)
                .define("NOMINMAX", None)
                .define("WIN32_LEAN_AND_MEAN", None);
        } else {
            build
                .define("WEBRTC_POSIX", None)
                .define("WEBRTC_LINUX", None);
        }
    }
    cpp.cpp(true).std("c++17");
    if windows {
        cpp.flag_if_supported("/EHsc")
            .flag_if_supported("/Zc:__cplusplus");
    }
    cpp.file("src/media/audio/neteq_bridge.cc");
    for source in sources
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
    {
        if source.ends_with(".cc") {
            cpp.file(root.join(source));
        } else if source.ends_with(".c") {
            c.file(root.join(source));
        }
    }
    cpp.compile("openuuyc_neteq");
    c.compile("openuuyc_neteq_dsp");
    if windows {
        println!("cargo:rustc-link-lib=winmm");
        println!("cargo:rustc-link-lib=ws2_32");
    } else {
        println!("cargo:rustc-link-lib=stdc++");
    }
}

fn build_pcvr_driver() {
    for path in [
        "drivers/pcvr/driver.cpp",
        "pico-android/app/src/main/cpp/pcvr_protocol.h",
        "drivers/pcvr/vendor/openvr_driver.h",
        "drivers/pcvr/driver.vrdrivermanifest",
    ] {
        println!("cargo:rerun-if-changed={path}");
    }
    let compiler = cc::Build::new().cpp(true).get_compiler();
    let output =
        std::path::PathBuf::from(std::env::var_os("OUT_DIR").unwrap()).join("driver_openuuyc.so");
    let status = compiler
        .to_command()
        .args([
            "-std=c++17",
            "-shared",
            "-fPIC",
            "-O2",
            "-fvisibility=hidden",
            "drivers/pcvr/driver.cpp",
            "-o",
        ])
        .arg(output)
        .status()
        .expect("compile built-in PCVR driver");
    assert!(status.success(), "built-in PCVR driver compilation failed");
}

fn build_pcvr_mirror() {
    println!("cargo:rerun-if-env-changed=PKG_CONFIG_ALLOW_CROSS");
    println!("cargo:rerun-if-env-changed=PKG_CONFIG_PATH");
    for path in [
        "drivers/pcvr/mirror.cpp",
        "drivers/pcvr/mirror_pose.h",
        "drivers/pcvr/vendor/openvr.h",
        "drivers/openxr/stereo_capture.c",
        "drivers/openxr/stereo_capture.h",
    ] {
        println!("cargo:rerun-if-changed={path}");
    }
    let root = std::path::PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    let output = root.join("openuuyc-steamvr-mirror");
    if std::env::var("HOST") != std::env::var("TARGET")
        && std::env::var_os("PKG_CONFIG_ALLOW_CROSS").is_none()
    {
        println!(
            "cargo:warning=Optional SteamVR mirror helper disabled for unconfigured cross-compilation"
        );
        std::fs::write(output, []).unwrap();
        return;
    }
    let flags = std::process::Command::new("pkg-config")
        .args(["--cflags", "--libs", "sdl2", "gl"])
        .output();
    let Ok(flags) = flags else {
        std::fs::write(output, []).unwrap();
        return;
    };
    if !flags.status.success() {
        println!(
            "cargo:warning=Optional SteamVR mirror helper disabled: SDL2/OpenGL development files missing"
        );
        std::fs::write(output, []).unwrap();
        return;
    }
    let compiler = cc::Build::new().get_compiler();
    let object = root.join("stereo_mirror_export.o");
    assert!(
        compiler
            .to_command()
            .args(["-std=c11", "-c", "drivers/openxr/stereo_capture.c", "-o"])
            .arg(&object)
            .status()
            .unwrap()
            .success(),
        "compile real stereo mirror exporter"
    );
    let compiler = cc::Build::new().cpp(true).get_compiler();
    let status = compiler
        .to_command()
        .args(["-std=c++17", "-O2", "drivers/pcvr/mirror.cpp"])
        .arg(&object)
        .args(String::from_utf8(flags.stdout).unwrap().split_whitespace())
        .arg("-ldl")
        .arg("-o")
        .arg(&output)
        .status()
        .unwrap();
    assert!(status.success(), "compile SteamVR background mirror helper");
}
