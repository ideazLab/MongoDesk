# MongoDesk release process

MongoDesk uses **tested main → version tag → four native builds → signed draft release → manual publication**.

## Current rollout

`VERSION` is prepared as `1.1.0`, replacing the previous app/installer mismatch (`0.1.0` / `1.0.0`). No release tag is created by merging this setup. Finish and test the in-app updater before tagging the first update-enabled release; release infrastructure alone does not add an updater to previously installed applications.

## Prepare a release

1. Merge the intended changes into `main` through a reviewed, passing pull request.
2. Set the shared version in `VERSION` to a stable `major.minor.patch` number. This drives Gradle, native installer versions, and the About dialog. Use a newer version for every published release.
3. Add user-facing notes under `## [VERSION]` in `CHANGELOG.md`.
4. Run `python3 packaging/release.py validate` and `./gradlew build`, commit the changes, and push `main`.
5. Wait until **Build desktop applications** passes for all four platforms on that exact commit.
6. Create and push an annotated tag matching `VERSION`. For the first release:

   ```sh
   git switch main
   git pull --ff-only
   python3 packaging/release.py validate
   git tag -a v1.1.0 -m "MongoDesk 1.1.0"
   git push origin v1.1.0
   ```

Never move or reuse a published release tag. Use patch releases for fixes, minor releases for backward-compatible features, and major releases for breaking behavior or data compatibility changes.

## What Actions does

The tag workflow verifies that:

- The tag matches `VERSION` and has a changelog entry.
- The tagged commit belongs to `main` and already has a successful complete main-branch build.
- All four native build/test jobs succeed again for the tag.

It then assembles these permanent release assets:

- `MongoDesk-VERSION-macos-arm64.dmg`
- `MongoDesk-VERSION-macos-x64.dmg`
- `MongoDesk-VERSION-windows-x64.msi`
- `MongoDesk-VERSION-linux-x64.deb`
- `update-manifest.json`
- `update-manifest.json.sig` — detached, base64-encoded Ed25519 signature over the exact manifest bytes
- `SHA256SUMS` — checksums for all installers and update metadata

The signed manifest identifies each platform, installer URL, SHA-256 hash, and byte size. The workflow creates a **draft** GitHub Release using the changelog notes. Normal branch/PR builds upload Actions artifacts and do not create releases. Re-running a tag workflow may refresh a draft, but it refuses to overwrite a published release.

## Review and publish

Open the draft under [GitHub Releases](https://github.com/ideazLab/MongoDesk/releases). Before clicking **Publish release**:

- [ ] Confirm the version, changelog, and complete seven-file asset set.
- [ ] Install and launch each platform/architecture package, or obtain platform-specific verification.
- [ ] Check that settings, saved connections, pipelines, tabs, and backup schedules survive an upgrade.
- [ ] Verify archive backup/restore against a disposable test database.
- [ ] Confirm update metadata signature and installer checksums.
- [ ] For the first update-enabled release, verify the client updater's check, download, installation/restart, failure recovery, and active-backup protection.
- [ ] Confirm the release is stable and should be offered to installed clients.

Click **Publish release** manually only after review. Drafts are not advertised by the public latest-release endpoint, so preparing a draft does not notify installed clients.

Once the client updater is integrated, its stable feed will use:

`https://github.com/ideazLab/MongoDesk/releases/latest/download/update-manifest.json`

with the corresponding `.sig` asset. Clients must verify the signature using the pinned public key before trusting installer URLs/hashes and must verify the download hash before installation. The agreed client policy is one automatic check per day plus **Help → Check for updates…**, followed by user-approved download and install/restart. Do not install during active backups.

## Signing and repository secrets

`MONGODESK_UPDATE_SIGNING_KEY` is the repository Actions secret containing the Ed25519 private key. It is only exposed to the trusted tag job's signing step. The public verification key is committed at `packaging/update-public-key.pem`. Signing fails if the secret is missing or does not match this public key. The local private key is in the Git-ignored `.signing/update-private-key.pem`, with owner-only permissions. Back it up securely; never commit it or replace the pinned public key without a client key-rotation plan.

Manifest signing is independent of OS application signing. Current CI installers are not Developer ID signed/notarized or Windows code signed. Local macOS builds still use the persistent local certificate. Adding platform signing credentials can be a later release-hardening step without changing this draft-review process.

Windows packages use a fixed upgrade UUID from `1.1.0` onward so future MSI releases can upgrade the existing installation. Pre-release Actions installers made before this process may require a one-time manual migration; they did not have this explicit stable upgrade identity.

## Failure and rollback

A failed build or signing step cannot create a publishable asset set. Fix the issue before publishing. If a release has already been published, do not replace its assets: pause further publication and prepare a higher patch version with the fix. Retain the previous release assets for manual recovery. The client updater must keep settings outside the installation directory and preserve a rollback path when replacing the application.
