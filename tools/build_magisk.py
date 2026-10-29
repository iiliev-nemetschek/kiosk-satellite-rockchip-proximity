#!/usr/bin/env python3
"""Package contrib/magisk/module as a Magisk module ZIP in dist/magisk/."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'contrib/magisk/module'
EXECUTABLE = {'service.sh', 'META-INF/com/google/android/update-binary'}
REQUIRED = {'module.prop', 'service.sh', 'META-INF/com/google/android/update-binary', 'META-INF/com/google/android/updater-script'}

files = sorted(p for p in MODULE.rglob('*') if p.is_file())
names = {p.relative_to(MODULE).as_posix() for p in files}
missing = REQUIRED - names
if missing:
    raise SystemExit(f'Module is missing: {", ".join(sorted(missing))}')
props = dict(line.split('=', 1) for line in (MODULE / 'module.prop').read_text().splitlines() if '=' in line)
for key in ('id', 'name', 'version', 'versionCode', 'author', 'description'):
    if not props.get(key):
        raise SystemExit(f'module.prop needs {key}')

out = ROOT / 'dist/magisk'
out.mkdir(parents=True, exist_ok=True)
package = out / f"{props['id']}-{props['version']}.zip"
with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in files:
        name = file.relative_to(MODULE).as_posix()
        info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o755 if name in EXECUTABLE else 0o644) << 16
        archive.writestr(info, file.read_bytes())
print(f'Magisk module: {package}')
