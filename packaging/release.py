#!/usr/bin/env python3
"""Validate release inputs, stage installers, sign update metadata, and create draft releases only."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'ideazLab/MongoDesk'
PLATFORMS = {
    'mongodesk-macos-arm64': ('macos-arm64', '.dmg'),
    'mongodesk-macos-x64': ('macos-x64', '.dmg'),
    'mongodesk-windows-x64': ('windows-x64', '.msi'),
    'mongodesk-linux-x64': ('linux-x64', '.deb'),
}


def version(root=ROOT):
    value = (root / 'VERSION').read_text().strip()
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', value):
        raise ValueError('VERSION must contain a stable major.minor.patch version without a v prefix.')
    major, minor, patch = map(int, value.split('.'))
    if not (1 <= major <= 255 and minor <= 255 and patch <= 65535):
        raise ValueError('VERSION is outside the supported native-installer version range.')
    return value


def notes(value, root=ROOT):
    text = (root / 'CHANGELOG.md').read_text()
    match = re.search(r'^## \[' + re.escape(value) + r'\][^\n]*\n(.*?)(?=^## |\Z)', text, re.M | re.S)
    if not match or not match.group(1).strip():
        raise ValueError(f'Add release notes under ## [{value}] in CHANGELOG.md.')
    return match.group(1).strip() + '\n'


def validate(tag=None, root=ROOT, require_main_ci=False):
    value = version(root)
    notes(value, root)
    if tag is not None:
        if tag != 'v' + value:
            raise ValueError(f'Release tag must be v{value}, matching VERSION.')
        subprocess.run(['git', 'merge-base', '--is-ancestor', 'HEAD', 'origin/main'], cwd=root, check=True)
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
        tagged = subprocess.check_output(['git', 'rev-parse', tag + '^{commit}'], cwd=root, text=True).strip()
        if head != tagged:
            raise ValueError('The tag does not point to the checked-out release commit.')
        if require_main_ci:
            runs = json.loads(subprocess.check_output(['gh', 'run', 'list', '--repo', REPOSITORY, '--workflow', 'build.yml',
                '--commit', head, '--branch', 'main', '--event', 'push', '--status', 'success', '--limit', '20',
                '--json', 'headSha,headBranch,conclusion'], text=True))
            if not any(r['headSha'] == head and r['headBranch'] == 'main' and r['conclusion'] == 'success' for r in runs):
                raise ValueError('Wait for all main-branch builds of this exact commit to succeed before releasing.')
    return value


def filename(value, artifact):
    platform, suffix = PLATFORMS[artifact]
    return f'MongoDesk-{value}-{platform}{suffix}'


def stage(artifact, package_glob, output, root=ROOT):
    value = version(root)
    if artifact not in PLATFORMS:
        raise ValueError('Unknown release platform.')
    files = list(root.glob(package_glob))
    if len(files) != 1 or not files[0].is_file() or files[0].stat().st_size == 0:
        raise ValueError('Expected exactly one nonempty native installer.')
    if files[0].suffix != PLATFORMS[artifact][1]:
        raise ValueError('Unexpected installer format.')
    output.mkdir(parents=True, exist_ok=True)
    target = output / filename(value, artifact)
    shutil.copyfile(files[0], target)
    return target


def sha256(file):
    digest = hashlib.sha256()
    with file.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def run_openssl(*args):
    return subprocess.run([os.environ.get('OPENSSL', 'openssl'), *map(str, args)], check=True, capture_output=True).stdout


def manifest(assets, tag, private_key, public_key, root=ROOT):
    value = version(root)
    if tag != 'v' + value:
        raise ValueError('Manifest tag must match VERSION.')
    if not private_key:
        raise ValueError('Configure the MONGODESK_UPDATE_SIGNING_KEY repository secret before tagging a release.')
    files = []
    for artifact, (platform, _) in PLATFORMS.items():
        name = filename(value, artifact)
        file = assets / name
        if not file.is_file() or file.stat().st_size == 0:
            raise ValueError(f'Missing installer: {name}')
        files.append({'platform': platform, 'name': name, 'size': file.stat().st_size, 'sha256': sha256(file),
            'url': f'https://github.com/{REPOSITORY}/releases/download/{tag}/{name}'})
    data = {'schemaVersion': 1, 'version': value, 'channel': 'stable', 'repository': REPOSITORY,
        'releaseUrl': f'https://github.com/{REPOSITORY}/releases/tag/{tag}', 'files': files}
    path = assets / 'update-manifest.json'
    path.write_bytes((json.dumps(data, indent=2, sort_keys=True) + '\n').encode('utf-8'))
    with tempfile.TemporaryDirectory(prefix='mongodesk-release-sign-') as directory:
        key = Path(directory) / 'private.pem'
        descriptor = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as stream:
            stream.write(private_key)
        expected = run_openssl('pkey', '-pubin', '-in', public_key, '-outform', 'DER')
        actual = run_openssl('pkey', '-in', key, '-pubout', '-outform', 'DER')
        if actual != expected or not actual.startswith(bytes.fromhex('302a300506032b6570032100')):
            raise ValueError('The signing secret does not match the pinned Ed25519 update public key.')
        signature = Path(directory) / 'signature.bin'
        run_openssl('pkeyutl', '-sign', '-rawin', '-inkey', key, '-in', path, '-out', signature)
        run_openssl('pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', public_key, '-in', path, '-sigfile', signature)
        (assets / 'update-manifest.json.sig').write_bytes(base64.b64encode(signature.read_bytes()) + b'\n')
    installers = [assets / item['name'] for item in files]
    checksum_files = installers + [path, assets / 'update-manifest.json.sig']
    (assets / 'SHA256SUMS').write_text(''.join(f'{sha256(file)}  {file.name}\n' for file in checksum_files))
    return data


def publish_draft(assets, tag, root=ROOT):
    value = version(root)
    if tag != 'v' + value:
        raise ValueError('Release tag must match VERSION.')
    expected = [filename(value, key) for key in PLATFORMS] + ['update-manifest.json', 'update-manifest.json.sig', 'SHA256SUMS']
    if any(not (assets / name).is_file() for name in expected):
        raise ValueError('The draft release asset set is incomplete.')
    existing = subprocess.run(['gh', 'release', 'view', tag, '--repo', REPOSITORY, '--json', 'isDraft'], text=True, capture_output=True)
    if existing.returncode == 0 and not json.loads(existing.stdout)['isDraft']:
        raise ValueError('This release is already published. Create a new version; published releases are never overwritten.')
    with tempfile.TemporaryDirectory(prefix='mongodesk-release-notes-') as temp:
        file = Path(temp) / 'notes.md'
        file.write_text(notes(value, root) + '\n## Installation\n\nChoose the installer matching your OS and CPU. '
            'These builds are not Developer ID signed/notarized or Windows code signed. '
            'Update metadata is independently signed with the pinned MongoDesk Ed25519 update key.\n')
        if existing.returncode == 0:
            subprocess.run(['gh', 'release', 'edit', tag, '--repo', REPOSITORY, '--draft=true', '--title', f'MongoDesk {value}', '--notes-file', str(file)], check=True)
            subprocess.run(['gh', 'release', 'upload', tag, '--repo', REPOSITORY, '--clobber', *[str(assets / name) for name in expected]], check=True)
        else:
            subprocess.run(['gh', 'release', 'create', tag, '--repo', REPOSITORY, '--draft', '--verify-tag', '--title', f'MongoDesk {value}', '--notes-file', str(file), *[str(assets / name) for name in expected]], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    check = commands.add_parser('validate')
    check.add_argument('--tag')
    check.add_argument('--require-main-ci', action='store_true')
    package = commands.add_parser('stage')
    package.add_argument('--artifact', choices=PLATFORMS, required=True)
    package.add_argument('--package-glob', required=True)
    package.add_argument('--output', type=Path, required=True)
    sign = commands.add_parser('manifest')
    sign.add_argument('--assets', type=Path, required=True)
    sign.add_argument('--tag', required=True)
    draft = commands.add_parser('draft')
    draft.add_argument('--assets', type=Path, required=True)
    draft.add_argument('--tag', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'validate':
            print('Validated release version', validate(args.tag, require_main_ci=args.require_main_ci))
        elif args.command == 'stage':
            print('Staged', stage(args.artifact, args.package_glob, args.output))
        elif args.command == 'manifest':
            manifest(args.assets, args.tag, os.environ.get('MONGODESK_UPDATE_SIGNING_KEY'), ROOT / 'packaging/update-public-key.pem')
            print('Created and verified signed update manifest and SHA256SUMS.')
        else:
            publish_draft(args.assets, args.tag)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Signing command output and environment values must never be echoed to CI logs.
        raise SystemExit(str(error) if isinstance(error, ValueError) else 'Release operation failed; check inputs, tools, and GitHub permissions.') from None

if __name__ == '__main__':
    main()
