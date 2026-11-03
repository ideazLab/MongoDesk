#!/usr/bin/env python3
"""Install a verified fresh bundle; never merge old resources into a signed app."""
from pathlib import Path
import os, plistlib, subprocess, sys, tempfile

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1]) if len(sys.argv) > 1 else root/'build/compose/binaries/main/app/MongoDesk.app'
destination = Path.home()/'Applications/MongoDesk.app'
expected = 'com.ideazlab.mongodesk'

def bundle_id(app):
    with (app/'Contents/Info.plist').open('rb') as info:
        return plistlib.load(info).get('CFBundleIdentifier')

def verify(app):
    if bundle_id(app) != expected:
        raise SystemExit('Unexpected bundle identifier; installation stopped.')
    subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict',str(app)],check=True)
    details = subprocess.run(['/usr/bin/codesign','-dv',str(app)],capture_output=True,text=True,check=True)
    if 'Signature=adhoc' in details.stderr:
        raise SystemExit('Refusing to install an ad-hoc signed build.')

verify(source)
processes = subprocess.check_output(['ps','-axo','comm='],text=True).splitlines()
if str(destination/'Contents/MacOS/MongoDesk') in [p.strip() for p in processes]:
    raise SystemExit('Quit MongoDesk before installing an update.')
if destination.exists() and bundle_id(destination) != expected:
    raise SystemExit('Destination contains another application; refusing to replace it.')
destination.parent.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='.mongodesk-install-',dir=destination.parent) as directory:
    staging = Path(directory)/'MongoDesk.app'
    previous = Path(directory)/'previous.app'
    subprocess.run(['/usr/bin/ditto',str(source),str(staging)],check=True)
    verify(staging)
    if destination.exists():
        os.rename(destination,previous)
    try:
        os.rename(staging,destination)
        verify(destination)
    except BaseException:
        if destination.exists(): os.rename(destination,Path(directory)/'failed.app')
        if previous.exists(): os.rename(previous,destination)
        raise
print('Installed and verified:',destination)
