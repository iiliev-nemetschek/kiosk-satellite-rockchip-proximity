#!/usr/bin/env python3
"""Run device-independent lifecycle and native protocol tests."""
import json, os, re, shutil, struct, subprocess, sys, tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]


def find_java():
    if os.environ.get('JAVA_HOME'):
        return Path(os.environ['JAVA_HOME'])
    if sys.platform == 'darwin':
        found = subprocess.run(['/usr/libexec/java_home'], capture_output=True, text=True)
        if found.returncode == 0 and found.stdout.strip():
            return Path(found.stdout.strip())
    return Path(shutil.which('javac')).resolve().parents[1]


java = find_java()
# The SDK's overlay classes use Android views. The tests never draw one,
# so the platform's stub classes are enough to compile and load them.
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = str(android_platform(sdk_root))
except ValueError as error:
    raise SystemExit(str(error)) from error

manifest = json.loads((root / 'kiosk-satellite-plugin.json').read_text())
assert manifest['apiVersion'] == 1 and manifest['id'] == 'rockchip-proximity-sensor'
assert len(manifest['settings']) <= 20
assert {s['type'] for s in manifest['settings']} == {'boolean', 'number'}
assert {'native', 'entities', 'host.control'} == set(manifest['capabilities'])
settings = {s['key']: s for s in manifest['settings']}
assert settings['nearThreshold']['default'] < settings['farThreshold']['default'], 'default hysteresis band must be positive'
assert settings['farThreshold']['default'] <= settings['maxRange']['default'], 'far threshold must be reachable below the clamp'
for setting in manifest['settings']:
    if setting['type'] == 'number':
        assert setting['min'] <= setting['default'] <= setting['max'], setting['key']
        assert (setting['max'] - setting['min']) % setting['step'] == 0, f"{setting['key']}: range must end on a step"
        assert (setting['default'] - setting['min']) % setting['step'] == 0, f"{setting['key']}: default must sit on a step"
        assert (setting['max'] - setting['min']) // setting['step'] <= 10000, f"{setting['key']}: at most 10000 slider steps"

# Community Plugins requirements from the plugin guidelines.
assert re.fullmatch(r'[0-9]{4}\.(?:[1-9]|1[0-2])\.(?:0|[1-9][0-9]{0,3})', manifest['minAppVersion']), 'minAppVersion names a Kiosk Satellite release'
assert [t['id'] for t in manifest['triggers']] == ['approach', 'leave']
icon = (root / 'assets' / manifest['icon']).read_bytes()
assert icon[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II', icon[16:24]) == (256, 256) and len(icon) <= 256 * 1024, 'icon: 256 by 256 PNG of at most 256 KB'
readme = (root / 'README.md').read_text()
assert '## Installation' in readme and 'redirect/plugin/?repository=iiliev-nemetschek/kiosk-satellite-rockchip-proximity' in readme, 'README needs the Installation section with the install button'
assert len(readme.encode()) <= 128 * 1024 and len((root / 'kiosk-satellite-plugin.json').read_bytes()) <= 32 * 1024

with tempfile.TemporaryDirectory(prefix='rockchip-proximity-test-') as temp:
    sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java')), *sorted((root / 'test').rglob('*.java'))]
    subprocess.run([str(java / 'bin/javac'), '--release', '8', '-cp', platform, '-d', temp, *map(str, sources)], check=True)
    classpath = os.pathsep.join([temp, platform])
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', classpath, 'me.jxl.kiosk.plugins.rockchipprox.ProximityTest'], check=True)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', classpath, 'ManifestContractTest'], check=True)
    native = Path(temp) / 'native-test'
    # jni.h includes jni_md.h from a per-OS subdirectory (linux/ or darwin/).
    md = next(iter(sorted((java / 'include').glob('*/jni_md.h'))), None)
    assert md is not None, f'no jni_md.h under {java}/include'
    # test/shim stands in for the NDK's <linux/input.h>, which a Mac does not have.
    subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', '-I' + str(root / 'test/shim'), '-I' + str(java / 'include'), '-I' + str(md.parent),
                    str(root / 'test/native_test.c'), '-o', str(native)], check=True)
    subprocess.run([str(native)], check=True)
print('PASS: manifest shape and native device lookup by name, non-blocking read, newest distance wins, error propagation and descriptor cleanup.')

subprocess.run([sys.executable, str(root / 'tools/test_android_sdk.py')], check=True)
subprocess.run([sys.executable, str(root / 'tools/test_plugin_manifest.py')], check=True)
subprocess.run([sys.executable, str(root / 'tools/test_plugin_assets.py')], check=True)
