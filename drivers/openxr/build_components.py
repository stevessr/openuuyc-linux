#!/usr/bin/env python3
"""Opt-in pinned source build. No root, global runtime changes, or downloaded binaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import sys
import subprocess
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
LOCK = json.loads((HERE / 'components.lock.json').read_text())

def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--monado-source', type=Path)
    parser.add_argument('--toolchain', default='1.95.0')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        raise SystemExit("Python 3.12+ is required for safe archive extraction")
    if args.jobs < 1:
        raise SystemExit("--jobs must be positive")
    work = args.work.resolve(); work.mkdir(parents=True, exist_ok=True)
    output = args.output.resolve()
    if output.exists():
        raise SystemExit('Output must not exist: never overwrite live runtime libraries')
    arch = platform.machine()
    if arch not in ('x86_64', 'aarch64'):
        raise SystemExit('Unsupported Linux architecture')
    monado = (args.monado_source or work / 'monado').resolve()
    if not monado.exists():
        run('git', 'clone', '--no-checkout', '--filter=blob:none', LOCK['monado']['git'], monado)
        run('git', '-C', monado, 'checkout', '--detach', LOCK['monado']['revision'])
    actual = subprocess.check_output(['git', '-C', str(monado), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != LOCK['monado']['revision'] or subprocess.check_output(['git', '-C', str(monado), 'status', '--porcelain'], text=True).strip():
        raise SystemExit('Monado source is not the clean pinned revision')
    archive = work / 'xrizer.tar.gz'
    if not archive.exists():
        with urllib.request.urlopen(LOCK['xrizer']['url'], timeout=90) as source, archive.open('wb') as dest:
            shutil.copyfileobj(source, dest)
    if digest(archive) != LOCK['xrizer']['sha256']:
        raise SystemExit('xrizer archive SHA-256 mismatch')
    # Build the verified extraction, not a possibly modified local checkout.
    verified = work / 'xrizer-verified'
    if verified.exists():
        raise SystemExit('Use a fresh work directory (xrizer-verified already exists)')
    verified.mkdir()
    with tarfile.open(archive, 'r:gz') as tar:
        tar.extractall(verified, filter='data')
    roots = list(verified.iterdir())
    if len(roots) != 1:
        raise SystemExit('Unexpected archive layout')
    xrizer = roots[0]
    # Preserve the pinned checkout. Compile an explicit, source-packaged overlay.
    patched = work / 'monado-openuuyc'
    if patched.exists():
        raise SystemExit('Use a fresh work directory (Monado overlay exists)')
    shutil.copytree(monado, patched, ignore=shutil.ignore_patterns('.git'))
    lists = patched / 'src/xrt/targets/common/target_lists.c'
    content = lists.read_text()
    marker = 'xrt_builder_create_func_t target_builder_list[] = {'
    if content.count(marker) != 1:
        raise SystemExit('Pinned Monado builder ABI changed')
    content = content.replace(marker, 'struct xrt_builder *t_builder_openuuyc_create(void);\n' + marker + '\n    t_builder_openuuyc_create,')
    lists.write_text(content)
    # Upstream currently always initializes a compositor even for MND_headless.
    # Respect device-only probes; normal graphical applications are unchanged.
    instance = patched / 'src/xrt/state_trackers/oxr/oxr_instance.c'
    content = instance.read_text()
    marker = 'bool should_create_compositor = true /* !inst->extensions.MND_headless */;'
    if content.count(marker) != 1:
        raise SystemExit('Pinned Monado headless hook changed')
    content = content.replace(marker, '#ifdef OXR_HAVE_MND_headless\n    bool should_create_compositor = !inst->extensions.MND_headless;\n#else\n    bool should_create_compositor = true;\n#endif')
    instance.write_text(content)
    common = patched / 'src/xrt/targets/common/CMakeLists.txt'
    with common.open('a') as target:
        target.write('\ntarget_sources(target_lists PRIVATE "'+str(HERE / 'pico_builder.c')+'")\n')
        target.write('target_include_directories(target_lists PRIVATE "'+str(HERE)+'")\n')
    build = work / 'monado-build'
    text = (monado / 'CMakeLists.txt').read_text()
    options = ['-D'+name+'=OFF' for name in sorted(set(re.findall(r'option(?:_with_deps)?\((XRT_BUILD_DRIVER_\w+)', text)))]
    # No simulated HMD, physical-device probing, extra daemon, or debug dashboard.
    options += ['-DXRT_FEATURE_NO_COMPOSITOR_FOR_HEADLESS_SESSIONS=ON', '-DXRT_FEATURE_SERVICE=OFF', '-DXRT_FEATURE_DEBUG_GUI=OFF', '-DXRT_FEATURE_OPENXR=ON',
                '-DXRT_FEATURE_OPENVR=OFF', '-DXRT_MODULE_MONADO_CLI=OFF', '-DXRT_MODULE_COMPOSITOR_NULL=OFF',
                '-DXRT_MODULE_COMPOSITOR_MAIN=ON', '-DXRT_OPENXR_INSTALL_ACTIVE_RUNTIME=OFF',
                '-DXRT_OXR_RUNTIME_SUFFIX=openuuyc']
    run('cmake', '-S', patched, '-B', build, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release', *options)
    run('cmake', '--build', build, '--target', 'openxr_openuuyc', '-j', args.jobs)
    env = os.environ.copy(); env['CARGO_TARGET_DIR'] = str(work / 'xrizer-target'); env['XRIZER_VERSION'] = '0.6.0-' + LOCK['xrizer']['revision'][:7]
    run('cargo', '+'+args.toolchain, 'build', '--locked', '--release', '--no-default-features', '--features', 'monado', cwd=xrizer, env=env)
    output.mkdir(parents=True)
    (output / 'lib').mkdir(); (output / 'openxr').mkdir(); (output / 'bin').mkdir()
    client_dir = output / 'xrizer/bin' / ('linux64' if arch == 'x86_64' else 'linuxarm64')
    client_dir.mkdir(parents=True)
    shutil.copy2(build / 'src/xrt/targets/openxr/libopenxr_openuuyc.so', output / 'lib/libopenxr_openuuyc.so')
    shutil.copy2(work / 'xrizer-target/release/libxrizer.so', client_dir / 'vrclient.so')
    run('c++', '-std=c++17', '-O2', HERE / 'runtime_probe.cpp', '-lopenxr_loader', '-o', output / 'bin/openuuyc-openxr-probe')
    (output / 'openxr/openxr_openuuyc.json').write_text(json.dumps({'file_format_version': '1.0.0', 'runtime': {'library_path': '../lib/libopenxr_openuuyc.so'}}, indent=2)+'\n')
    licenses = output / 'licenses'; licenses.mkdir()
    shutil.copytree(monado / 'LICENSES', licenses / 'monado')
    shutil.copy2(monado / 'LICENSE', licenses / 'monado-LICENSE')
    shutil.copy2(xrizer / 'LICENSE', licenses / 'xrizer-LICENSE')
    # Ship corresponding pinned source archives separately from the host executable.
    sources = output / 'sources'; sources.mkdir()
    run('git', '-C', monado, 'archive', '--format=tar.gz', '--prefix=monado/', '-o', sources / 'monado.tar.gz', LOCK['monado']['revision'])
    shutil.copy2(archive, sources / 'xrizer.tar.gz')
    with tarfile.open(sources / 'openuuyc-overlay.tar.gz', 'w:gz') as archive_out:
        for name in ('build_components.py', 'components.lock.json', 'pico_builder.c', 'device_ipc.h', 'runtime_probe.cpp'):
            archive_out.add(HERE / name, arcname='openuuyc-openxr/' + name)
    files = {str(p.relative_to(output)): digest(p) for p in output.rglob('*') if p.is_file()}
    (output / 'bundle.json').write_text(json.dumps({'version': 1, 'features': ['pico_device_v1'], 'arch': arch, 'monado_revision': actual, 'xrizer_revision': LOCK['xrizer']['revision'], 'files': files}, indent=2)+'\n')
    print('Built component bundle:', output)
    print('Runtime includes the PICO device builder; GPU compositor streaming remains unavailable.')

if __name__ == '__main__':
    main()
