#!/usr/bin/env python3
"""Run device-independent lifecycle and native protocol tests."""
import json, os, shutil, subprocess, sys, tempfile
from pathlib import Path

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

with tempfile.TemporaryDirectory(prefix='rockchip-proximity-test-') as temp:
    sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java')), *sorted((root / 'test').rglob('*.java'))]
    subprocess.run([str(java / 'bin/javac'), '--release', '8', '-d', temp, *map(str, sources)], check=True)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', temp, 'me.jxl.kiosk.plugins.rockchipprox.ProximityTest'], check=True)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', temp, 'ManifestContractTest'], check=True)
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
subprocess.run([sys.executable, str(root / 'tools/test_plugin_assets.py')], check=True)
