#!/usr/bin/env python3
"""Build desktop icon formats from the approved Workspace PNG (macOS build tool)."""
from pathlib import Path
import struct
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = root / 'packaging/branding/workspace-master.png'
icons = root / 'packaging/icons'

def resize(size, destination):
    subprocess.run(['/usr/bin/sips', '-z', str(size), str(size), str(source), '--out', str(destination)], check=True, stdout=subprocess.DEVNULL)

resize(1024, icons / 'MongoDesk.png')
resources = root / 'src/main/resources/branding'
resources.mkdir(parents=True, exist_ok=True)
(resources / 'mongodesk-icon.png').write_bytes((icons / 'MongoDesk.png').read_bytes())
with tempfile.TemporaryDirectory(prefix='mongodesk-icons-') as temporary:
    folder = Path(temporary)
    iconset = folder / 'MongoDesk.iconset'
    iconset.mkdir()
    for base in (16, 32, 128, 256, 512):
        resize(base, iconset / f'icon_{base}x{base}.png')
        resize(base * 2, iconset / f'icon_{base}x{base}@2x.png')
    subprocess.run(['/usr/bin/iconutil', '-c', 'icns', str(iconset), '-o', str(icons / 'MongoDesk.icns')], check=True)
    images = []
    for size in (16, 24, 32, 48, 64, 128, 256):
        file = folder / f'{size}.png'
        resize(size, file)
        images.append((size, file.read_bytes()))
    offset = 6 + 16 * len(images)
    header = bytearray(struct.pack('<HHH', 0, 1, len(images)))
    for size, data in images:
        header.extend(struct.pack('<BBBBHHII', size if size < 256 else 0, size if size < 256 else 0, 0, 0, 1, 32, len(data), offset))
        offset += len(data)
    (icons / 'MongoDesk.ico').write_bytes(bytes(header) + b''.join(data for _, data in images))
print('Generated PNG, ICNS, ICO, and bundled Compose icon from Workspace master.')
