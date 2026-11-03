"""Install verified official MongoDB Database Tools privately for MongoDesk."""
from pathlib import Path
import hashlib, io, json, os, platform, shutil, tarfile, tempfile, urllib.request, zipfile
root = Path.home()/'.mongodesk'/'tools'
name = 'macos' if platform.system() == 'Darwin' else 'windows' if platform.system() == 'Windows' else None
arch = 'arm64' if platform.machine() in ('arm64', 'aarch64') else 'x86_64'
with urllib.request.urlopen('https://downloads.mongodb.org/tools/db/release.json', timeout=30) as response: metadata=json.load(response)
release=metadata['versions'][0]
choices=[item for item in release['downloads'] if item['name']==name and item['arch'] in (arch, 'aarch64' if arch=='arm64' else arch)]
if not choices: raise SystemExit('No matching official tools archive; install MongoDB Database Tools manually.')
entry=choices[0]['archive']
if not entry['url'].startswith('https://fastdl.mongodb.org/tools/db/'): raise SystemExit('Unexpected download host')
with urllib.request.urlopen(entry['url'],timeout=120) as response: archive=response.read()
if hashlib.sha256(archive).hexdigest()!=entry['sha256']: raise SystemExit('Archive checksum mismatch')
root.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='install-',dir=root) as temp:
    stage=Path(temp)
    if entry['url'].endswith('.zip'):
        with zipfile.ZipFile(io.BytesIO(archive)) as package:
            members={Path(item).name:package.read(item) for item in package.namelist() if not item.endswith('/') and (Path(item).name in ('mongodump','mongorestore','mongodump.exe','mongorestore.exe') or Path(item).name.startswith(('LICENSE','THIRD-PARTY','THIRD_PARTY','README')))}
    else:
        with tarfile.open(fileobj=io.BytesIO(archive),mode='r:gz') as package:
            members={Path(item.name).name:package.extractfile(item).read() for item in package.getmembers() if item.isfile() and (Path(item.name).name in ('mongodump','mongorestore') or Path(item.name).name.startswith(('LICENSE','THIRD-PARTY','THIRD_PARTY','README')))}
    for filename,data in members.items():
        target=stage/filename;target.write_bytes(data)
        if filename.startswith(('mongodump','mongorestore')):target.chmod(0o755)
    (root/'bin').mkdir(exist_ok=True)
    for item in stage.iterdir():os.replace(item,root/'bin'/item.name)
(root/'version.txt').write_text(release['version']+'\n'+entry['url']+'\n'+entry['sha256']+'\n')
print('Installed verified MongoDB Database Tools',release['version'],'in',root/'bin')
