#!/usr/bin/env python3
"""Sign bundled native code inside-out with MongoDesk's persistent local identity."""
from pathlib import Path
import os, subprocess, sys

root = Path(__file__).resolve().parents[1]
settings = root / '.signing/macos.properties'
if not settings.exists():
    raise SystemExit('No persistent signing identity. Run python3 packaging/bootstrap-macos-signing.py once. Ad-hoc signing is not used as a fallback.')
config = dict(line.split('=', 1) for line in settings.read_text().splitlines() if '=' in line)
identity = os.environ.get('MONGODESK_SIGNING_IDENTITY', config['identity'])
keychain = config['keychain']
app = Path(sys.argv[1]).resolve()
if not app.is_dir() or app.suffix != '.app':
    raise SystemExit('Expected an existing .app bundle')
identities = subprocess.check_output(['/usr/bin/security','find-identity','-v','-p','codesigning',keychain],text=True)
if identity not in identities:
    raise SystemExit('Configured signing identity is unavailable. Unlock the login keychain; do not create a replacement identity.')

mach_headers = {bytes.fromhex(value) for value in ['feedface','feedfacf','cefaedfe','cffaedfe','cafebabe','bebafeca','cafebabf','bfbafeca']}

def sign(path):
    subprocess.run(['/usr/bin/codesign', '--force', '--sign', identity, '--keychain', keychain, '--timestamp=none',
        '--preserve-metadata=identifier,entitlements,flags,runtime', str(path)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)

for file in sorted(app.rglob('*'), key=lambda p: len(p.parts), reverse=True):
    if file.is_file() and not file.is_symlink():
        with file.open('rb') as stream:
            if stream.read(4) in mach_headers:
                sign(file)
bundles = {p for p in app.rglob('*') if p.is_dir() and p.suffix in ('.app','.framework','.bundle')}
bundles.update(plist.parent.parent for plist in app.rglob('Info.plist') if plist.parent.name == 'Contents')
for bundle in sorted(bundles - {app}, key=lambda p: len(p.parts), reverse=True):
    sign(bundle)
sign(app)
subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict','--verbose=2',str(app)],check=True)
print('Signed MongoDesk with persistent identity',identity)
subprocess.run(['/usr/bin/codesign','-d','-r-',str(app)],check=True)
