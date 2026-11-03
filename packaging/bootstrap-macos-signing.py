#!/usr/bin/env python3
"""Create/reuse a local MongoDesk signing identity; never export an existing key."""
from pathlib import Path
import os, secrets, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
config_dir = root / '.signing'
config_dir.mkdir(mode=0o700, exist_ok=True)
cert = config_dir / 'certificate.pem'
settings = config_dir / 'macos.properties'
name = 'MongoDesk Local Development'
keychain = Path.home() / 'Library/Keychains/login.keychain-db'

def run(args, **kwargs):
    try:
        return subprocess.run(args, check=True, **kwargs)
    except subprocess.CalledProcessError:
        # Do not include argv in errors: import uses a random one-use P12 password.
        raise SystemExit("Signing setup failed while running " + Path(args[0]).name + ". Check the OS authorization prompt.")

if not cert.exists():
    existing = subprocess.run(['/usr/bin/security', 'find-certificate', '-c', name, '-p', str(keychain)], capture_output=True)
    if existing.returncode == 0:
        cert.write_bytes(existing.stdout)
    else:
        with tempfile.TemporaryDirectory(prefix='mongodesk-signing-') as scratch:
            tmp = Path(scratch)
            conf = tmp / 'certificate.cnf'
            conf.write_text('''[req]
prompt=no
distinguished_name=dn
x509_extensions=extensions
[dn]
CN=MongoDesk Local Development
O=Ideazlab
[extensions]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always
''')
            run(['openssl', 'req', '-new', '-x509', '-newkey', 'rsa:3072', '-noenc', '-sha256', '-days', '3650',
                 '-config', str(conf), '-keyout', str(tmp/'private.pem'), '-out', str(tmp/'certificate.pem')], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            password = secrets.token_urlsafe(32)
            password_file = tmp / 'bundle-password'
            password_file.write_text(password)
            password_file.chmod(0o600)
            run(['openssl', 'pkcs12', '-export', '-legacy', '-inkey', str(tmp/'private.pem'), '-in', str(tmp/'certificate.pem'),
                 '-name', name, '-out', str(tmp/'identity.p12'), '-passout', 'file:'+str(password_file)], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            # The one-use bundle password is random, not the user's login/keychain password.
            # Only codesign is pre-authorized; the key is made non-extractable.
            run(['/usr/bin/security', 'import', str(tmp/'identity.p12'), '-k', str(keychain), '-f', 'pkcs12',
                 '-P', password, '-x', '-T', '/usr/bin/codesign'])
            cert.write_bytes((tmp/'certificate.pem').read_bytes())

# Trust only this certificate's code-signing use in the current user's domain.
run(['/usr/bin/security', 'add-trusted-cert', '-r', 'trustRoot', '-p', 'codeSign', '-k', str(keychain), str(cert)])
fingerprint = subprocess.check_output(['openssl', 'x509', '-in', str(cert), '-noout', '-fingerprint', '-sha1'], text=True).strip().split('=', 1)[1].replace(':', '')
identities = subprocess.check_output(['/usr/bin/security', 'find-identity', '-v', '-p', 'codesigning', str(keychain)], text=True)
if fingerprint not in identities:
    raise SystemExit('The existing signing certificate has no available private key. Unlock the keychain; do not rotate the identity automatically.')
settings.write_text('identity='+fingerprint+'\nkeychain='+str(keychain)+'\n')
settings.chmod(0o600)
print('MongoDesk signing identity configured:', fingerprint)
run(['/usr/bin/security', 'find-identity', '-v', '-p', 'codesigning', str(keychain)])
