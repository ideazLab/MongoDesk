# MongoDesk

[![Build desktop applications](https://github.com/ideazLab/MongoDesk/actions/workflows/build.yml/badge.svg)](https://github.com/ideazLab/MongoDesk/actions/workflows/build.yml)

Personal MongoDB desktop workspace built with Kotlin and Compose Desktop. Custom Compose Foundation controls with Doar-inspired colours and compact panels. No Material UI components are used.

## Run

Install JDK 21, then run `./gradlew run` (Windows: `gradlew.bat run`). On macOS, if your default JDK is newer, use:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew run
```

Open this folder in IntelliJ IDEA as a Gradle project.

## Connect and browse

1. Open **Connections**. Enter a name and MongoDB URI. Authentication, authSource and TLS options come from the URI, including `mongodb+srv://` for direct connections.
2. Optionally specify a database. If supplied, or included in the URI path, the explorer is restricted to that database. This supports users without listDatabases permission.
3. Choose **Direct** or **SSH tunnel**. For SSH, supply the jump host, port and username; choose password or a private key with optional passphrase. The MongoDB URI hostname is the target reachable from the SSH server.
4. **Test** and **Connect** both ping MongoDB and initialize the explorer. Database discovery requests only authorized databases. When a database is specified, both check collection access instead of global discovery. Test closes the connection afterward; Connect keeps it open. **Save connection** remembers details without opening a network connection. **Connect** saves the profile before connecting. A failed connection attempt therefore still retains your saved settings.
5. Select a database and collection. Documents load in pages of 50, ordered by `_id`. Next/Previous retain the applied filter; Refresh reloads the current page. Run query applies the edited Extended JSON filter from page one.
6. Switch Table/JSON and select a document for the canonical Extended JSON inspector. BSON ObjectIds, dates, decimals and 64-bit integers retain their types.

### SSH and TLS

SSH binds an ephemeral port on 127.0.0.1 only. Host keys must already be trusted in the selected known_hosts file; unknown or changed keys are rejected. Password and file-based private-key authentication are supported; SSH agents and jump chains are not yet supported.

Tunnels currently connect directly to one `mongodb://host:port` endpoint. SRV, multiple seed hosts and replicaSet discovery through SSH are rejected. Direct connections retain driver support for those topologies. TLS settings are preserved; certificate hostname checking is not disabled automatically. Because the tunneled driver endpoint is localhost, TLS certificates must validate for that endpoint; remote-host-only certificates need further tunnel/TLS routing work.

### Saved profiles

Connection details are encrypted locally with AES-256-GCM in `~/.mongodesk/credentials/<profile-id>.enc`. The random encryption key is stored at `~/.mongodesk/credentials.key`. POSIX directories use mode 700 and files mode 600; Windows uses an owner-only ACL. The key is local, so this is not master-password protection against someone who can read files as your account. Back up the credential directory and key together; a missing or mismatched key cannot be silently regenerated without losing access to existing ciphertext.

The `~/.mongodesk/connections.json` index contains profile names, UUIDs and the last-used selection. Normal save, connect and edit operations do not access Keychain, Windows Credential Manager or Secret Service. Named profiles appear in searchable dropdowns, with **New connection** as the default/empty option. The sidebar connects directly to saved profiles; the top-bar **Connections** button opens profile settings. **Save changes** updates a profile and **Save as new connection** keeps an independent copy. Failed connection switches leave the existing connection open.

Profiles from earlier versions can be moved using **Connections → Move existing profiles from Keychain → Confirm move**. This is the only connection feature that accesses the old OS credential store: it reads each legacy entry once, writes and verifies its encrypted local copy, then removes the old entry. It may need one final OS authorization. Failed cleanup is tracked for retry, with the local copy retained; finish that cleanup before deleting a legacy profile. Alternatively, re-enter details and save locally, then finish the legacy cleanup using the same move action.

## Delete saved connections

Select a saved profile from the searchable dropdown, choose **Delete connection**, then confirm **Delete permanently**. This removes the selected metadata and encrypted local credential file. If the profile is active, it is disconnected. Database contents and SSH key files are untouched. Cancelling leaves the profile in place. If local credential deletion fails, the metadata is retained for retry. Deleting the final profile leaves only **New connection** in the dropdown.

## Build and run aggregations

Select a database and collection, then open **Aggregation**. The stage builder starts with an empty `$match` filter. Use **+ Add stage** to add a template, select a stage on the left, and edit its operator and Extended JSON body. **Enabled/Disabled**, the arrow buttons, and **Remove** control which stages run and their order. Templates include match, group, project, sort, limit, skip, unwind, lookup, count, addFields, unset and facet. Other server-supported read-only operators can be entered in the operator field or JSON editor.

Switch to **Pipeline JSON** to edit/paste a full array of stage objects, or switch back to **Stage builder**. Unchanged JSON preserves disabled builder drafts; editing the JSON and returning reconstructs the builder from that pipeline. **Run pipeline** executes enabled stages against the selected collection. Table/JSON results, the inspector, Previous/Next and Refresh work with aggregated output. Pages contain 50 results, with skip/limit appended after your complete pipeline, preserving your pipeline's own limit/skip/group semantics. Use an explicit `$sort` if stable ordering matters.

Runs are asynchronous, cancellable, and limited to 30 seconds of server execution. The runner displays validation errors, result counts and elapsed time. `$out` and `$merge` are rejected because this workspace is read-only. Use **Save pipeline** to persist a draft; edits made after saving remain in memory until saved again. Queries and aggregation results are cleared when switching between those workspaces to avoid confusing one with the other.

Tests execute match/group/project/sort pipelines and output pagination against an in-process MongoDB protocol fixture, and check parsing, stage ordering, disabled stages, BSON round trips, and rejected write stages. Real-server features such as lookup/facet depend on the connected MongoDB version and permissions.

## Saved aggregation pipelines

The Aggregation tab has a searchable **Saved pipelines** dropdown and a pipeline-name field. **Save pipeline / Save changes** stores the current draft; **Save as new** creates an independent copy. Choose **New pipeline** for a fresh builder. **Delete** asks for confirmation before removing the selected saved pipeline. Saving does not execute any database operation.

The library is stored in `~/.mongodesk/pipelines.json` with owner-only permissions on POSIX systems and atomic file replacement. It preserves multiple named pipelines, stage order, enabled/disabled flags, exact stage-body text, JSON editor text and editor mode. Enabled stages must be valid before saving; disabled incomplete stage drafts can be retained. The last-used pipeline is restored on application startup without connecting or running it automatically.

Database and collection names are saved as reference context. Loading a pipeline does not switch your connection or collection: execution always uses the collection currently selected in the workspace. These files may contain query literals and are local JSON files, separate from the encrypted local connection credentials. Corrupt library files are reported rather than silently replaced. Unsaved edits are indicated and require another save to survive restarting.

## Edit and update a document

In **Documents** or **Query**, select a row and choose **Edit document** in the inspector. Edit the full Extended JSON document, then choose **Review changes**. Review the before/after documents and added/removed/changed top-level fields, then choose **Confirm update** to write to the displayed connection and collection. The `_id` value and BSON type must be unchanged. Omitted fields are removed because this operation replaces the full document. Unsaved edits can be discarded explicitly.

Updates use an atomic compare-and-replace filter containing the original `_id` and full original document, with binary string collation and upsert disabled. This detects differences in the stored document according to MongoDB equality semantics, including fields added by another client; it is not a version-history check and cannot detect changes that return to the original value. Conflicts leave the draft available and require cancelling and refreshing before editing the latest version. Unacknowledged `w=0` connections cannot save documents. If a write cannot be confirmed because of a network failure, refresh before retrying because the server may have applied it.

After a confirmed save, the result page is refreshed using the applied query. A document that no longer matches may disappear from the results. Aggregation results remain read-only, because transformed or grouped documents are not safe source-document edit targets. Tests perform writes only against temporary local fixtures, covering field additions/removals, nested changes, BSON fidelity, concurrent additions, deleted records and unacknowledged write rejection.

## Add and delete documents

In **Documents** or **Query**, select a collection and choose **New document**. Enter one Extended JSON object. If `_id` is omitted, an ObjectId is generated and displayed in **Review document** before any write. You may supply your own `_id`, except an array. **Confirm insert** writes the reviewed document; duplicate IDs or unique-index values and schema-validation failures are reported. Closing a modified draft asks whether to discard it.

Select a source document and choose **Delete document** in the inspector. The dialog displays the exact connection, database, collection, `_id` and document. Only **Delete permanently** performs deletion. It uses the same atomic original-document snapshot guard as editing: if the document changed or disappeared after loading, deletion is refused and the collection must be refreshed. This is one-document deletion only, with no in-app undo. Aggregation output cannot be deleted through the inspector.

Both operations require acknowledged writes and refresh the current result page on confirmed success. If the page becomes empty after deletion, the previous page is loaded. Inserted documents may fall outside the active query or current page; the inserted `_id` is shown in the status message. If a network error prevents confirmation, inspect refreshed data before retrying. Tests use temporary local fixture documents only, including generated/explicit IDs, BSON fidelity, duplicate IDs, exact-target deletion, stale/deleted records and unacknowledged-write rejection.

## Session credential cache

After a profile is successfully unlocked or saved, the complete connection configuration is cached in process memory and shared by the sidebar and connection editor. Reconnecting, switching back to that profile, and reopening its editor reuse the cached values. Saving an unchanged profile does not rewrite its encrypted local file. Changed values replace the cache only after successful persistence; deletion removes the cached entry. Failed reads are not cached, and simultaneous reads of one profile share a single backing read.

The cache is never written to a separate file. It lasts until MongoDesk exits and starts empty on the next launch; each saved profile is decrypted locally on its first access without an OS credential prompt. External local-file changes are picked up after restarting MongoDesk. Tests verify reuse across separate profile-repository instances, failed read/write handling, deletion, concurrent reads, and clearing while a read is in flight.

## Current limits

Live browsing and Extended JSON filters are implemented. Projection/sort controls for find queries and saved find queries remain follow-ups. Single-document insertion, replacement and deletion are supported. Bulk mutations are not implemented. Query results use offset pagination and are not a snapshot if data changes while browsing. System typography is used; bundled IBM Plex Sans remains a follow-up.

## Verification

Run `./gradlew build`. Tests cover BSON type preservation, validation/error redaction, profile round trips, encrypted local storage, migration and storage-failure behaviour, plus direct and SSH/password browsing against temporary in-process MongoDB protocol and SSH test servers. Integration tests exercise filtering, pagination, host-key rejection and tunnel socket cleanup. Regression tests reproduce a successful ping followed by denied database discovery, verify shared workspace checks and ensure error messages omit server-provided secrets. These fixtures do not replace testing against your real MongoDB topology, authentication and TLS configuration.

## macOS desktop app

The local app is installed at `~/Applications/MongoDesk.app`, with a Desktop shortcut named **MongoDesk.app**. Double-clicking launches it directly without Terminal or Gradle. It includes its own Java runtime and the Workspace app icon. Only one instance can run per OS user, including development launches. Reopening activates the existing window; an OS file lock releases automatically on exit or crash. Local activation uses an authenticated loopback socket with a private endpoint file.

The installed app is a packaged snapshot. After source changes, quit the installed app and refresh it with:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew installMacosApp
```

The installer verifies a fresh staging bundle, replaces the previous application atomically, and verifies the installed signature. It refuses to update a running app or install an ad-hoc build. Do not merge a build into an old app with ditto; leftover JARs invalidate the sealed resources. The Desktop shortcut continues to point to the installed app. The old Terminal launcher is archived at `packaging/MongoDesk-terminal.command`. The approved Workspace identity uses navy/cobalt document panes forming an M. The master is `packaging/branding/workspace-master.png`; run `python3 packaging/icons/generate-icons.py` on macOS to regenerate PNG, ICNS, ICO, and the bundled Compose icon. The old Swift icon renderer is archived at `packaging/branding/GenerateLegacyIcon.swift`.

## Persistent macOS signing

This Mac uses the **MongoDesk Local Development** identity stored in the login keychain. Its private key is non-extractable and authorized for `/usr/bin/codesign`; trust is restricted to code signing in the current user domain. Existing connection credential permissions are not broadened by this setup.

Machine-local public certificate/fingerprint configuration is under `.signing/` (ignored by Git). Do not delete or recreate this identity between builds. The app's designated requirement is its fixed bundle identifier plus the persistent certificate fingerprint. `createDistributable` and `createReleaseDistributable` sign the native code and nested runtime bundles inside-out and verify the resulting app. Missing/unavailable identities fail packaging rather than falling back to ad-hoc signatures.

For a fresh Mac, run `python3 packaging/bootstrap-macos-signing.py` once. It reuses an existing dedicated certificate if found; otherwise it creates a local code-signing-only certificate valid for ten years. Temporary key/P12 files are removed after import; the repository never contains the private key. macOS may require authorization to import/trust the local certificate and let codesign use the key. Sign normally thereafter with the standard app-image build command. Do not rotate identities automatically when a keychain is locked.

The signing key remains in Keychain for build-time codesign use. Connection details are stored separately in encrypted local files; app startup, connecting and editing no longer use Keychain after legacy migration. Local signing is for this personal installation; it is not Apple Developer ID signing or notarization for distributing an app to other Macs. Verification includes strict nested-signature checks and comparison of designated requirements before/after changing and re-signing a temporary app copy.

## Packaging

Run `./gradlew packageDistributionForCurrentOS` with JDK 21. DMG, MSI and DEB targets are configured; build each installer on its respective operating system. Local macOS app signing is configured as described below. Apple Developer ID signing, notarization and public distribution are not configured.

## Implementation references

- [MongoDB Kotlin coroutine driver](https://www.mongodb.com/docs/drivers/kotlin/coroutine/current/getting-started/)
- [SSHJ local forwarding example](https://github.com/hierynomus/sshj/blob/master/examples/src/main/java/net/schmizz/sshj/examples/LocalPF.java)
- [java-keyring](https://github.com/javakeyring/java-keyring)


## Database export and import

Use Export or Import in the app bar, or right-click a database in the sidebar. Right-click a collection to open it, export the collection or its current query result, or close its tab. Export is a three-step wizard with Entire Database, Entire Collection/View, Current Query Result, and Selected Document sources. Database/collection exports are gzip-compressed mongodump archives containing BSON data and collection/index/view metadata. Collection/view, query-result, and selected-document exports can use JSON arrays, JSON Lines, or CSV. JSON uses canonical Extended JSON to preserve BSON types. CSV includes every top-level field encountered across all documents; missing fields are blank, and nested objects/arrays use Extended JSON text. CSV rows are spooled to private temporary files rather than retained in memory. A query export reruns the last applied query or aggregation over all results, not just the displayed page.

Database import accepts compressed or uncompressed mongodump archives and reads the database names from the archive. Choose a destination connection and database, then New database, Merge, or Replace matching collections. Replace drops only collections included in the archive, leaving unrelated collections untouched. Import requires typing the destination name to confirm. An interrupted import can leave partial changes; it is not rolled back. Pause application writes during export when a consistent live database backup is required.

Archives require MongoDB Database Tools 100.3 or later. MongoDesk searches its private `~/.mongodesk/tools/bin` folder, common installation folders, and PATH. A different bin folder can be selected in the wizard. `python3 packaging/install-database-tools.py` installs checksum-verified official tools privately on supported macOS/Windows machines; on Linux, install the official Database Tools and select their bin folder. The installer preserves vendor license files. Connection URIs are provided via temporary owner-only configuration files and removed on completion or cancellation; credentials are omitted from process arguments and redacted in tool messages.

Archive transfer supports direct connections and non-TLS SSH tunnels. SSH plus TLS archive transfer requires a direct TLS connection; query/document Extended JSON export uses the existing session. Exported database files are not encrypted.


## Server information

Right-click an active connection, then Server Info → Server Status or Build Info. Commands run read-only against that connection's admin database, with a 20-second client timeout and 10-second server limit. Results open in a modal with Tree/JSON views, Refresh, and Copy JSON. Accounts without serverStatus permission receive monitoring-role guidance; failure does not disconnect the session or block buildInfo. Sidebar menus retain their row-matched font sizes.


## Scheduled database backups

Use **Backups → Manage schedules… / Schedule database backup…** in the top toolbar, or right-click a database and choose **Schedule database backup…**.

Choose a saved connection, database, destination folder, local start date/time, IANA time zone, and a one-off or repeating interval (minutes, hours, days, weeks). A saved schedule registers a per-user background worker. It runs when the UI is closed, while the account is logged in and the computer is awake. macOS uses a LaunchAgent, Windows uses Task Scheduler, and Linux uses a systemd user timer. The installed application is required; launching via Gradle does not register a background worker.

The worker checks every minute. Overdue schedules run once and then advance from their original start time; they do not replay every missed interval. Daily/weekly schedules preserve local wall-clock times across daylight-saving changes. If clocks skip the configured start time, choose another start time. Pause/resume, run now, edit, remove, and recent results are available in the manager. Removing a schedule keeps archives and history and does not interrupt an already running backup.

Archives are uniquely named `database-UTC-timestamp-run-id.archive.gz`, written with owner-only permissions, and retain collection data, indexes, and metadata. They are not encrypted. No archives are automatically deleted. The archive header is checked before a run is marked successful; this is not a full restore verification. Live application writes are not transactionally snapshotted. The existing archive export SSH/TLS limitations also apply.

Schedules/history are stored atomically in `~/.mongodesk/backups/schedules.json`; they contain profile IDs, not credentials. The worker reads the existing encrypted local connection profiles and opens its own MongoDB/SSH session. Worker and state-file locks prevent overlapping executions and conflicting edits; recent history retains 200 runs. A deleted or unavailable profile causes the backup to fail visibly in history. Failures advance to the next planned interval; **Run now** can retry earlier. A process interrupted during a run is recorded as interrupted when the worker next starts.

Worker entry point: the installed MongoDesk executable with `--backup-worker`. It bypasses the GUI single-instance lock, creates no application windows, and exits after checking due schedules. macOS registration is `~/Library/LaunchAgents/com.ideazlab.mongodesk.backups.plist`; the manager's **Register background worker** action can repair registration after moving the installed app.


## Native application menu bar

MongoDesk uses native AWT/Cocoa menus on macOS and Compose Desktop's window menu bar on Windows and Linux. On macOS it appears in the system menu bar; on Windows and Linux it appears in the application window. macOS also uses the standard application menu for About, Hide, Services, and Quit. About opens MongoDesk's own version dialog, and Quit follows the normal application shutdown path. Background backups run in a separate worker and continue after quitting the UI.

Menus expose File, Edit, Connection, Database, Collection, Document, View, Backups, and Help. Database actions follow the connection/database selected in the sidebar; collection/document actions follow the active collection tab and selected result. Actions that need a connection, collection, or selected document are disabled when unavailable. Aggregation results remain read-only, and destructive menu commands open the existing confirmation dialogs. There is no separate macOS menu row inside the content area; the app's quick-action toolbar remains available.

Platform shortcuts use Command on macOS and Control elsewhere: N (new connection), K (connections), W (close collection tab), Enter (run query/pipeline), R (refresh), Shift+N (create document), and Shift+D (dashboard). Editing controls keep their normal platform editing shortcuts; the Edit menu forwards editing actions to the focused control.


## GitHub Actions builds

The **Build desktop applications** workflow runs on pushes to `main`, version tags (`v*`), pull requests, and manual **Run workflow** requests. Its four jobs build on their native operating systems:

| Target | Runner | Download artifact | Installer |
| --- | --- | --- | --- |
| macOS Apple Silicon | `macos-15` | `mongodesk-macos-arm64` | DMG |
| macOS Intel | `macos-15-intel` | `mongodesk-macos-x64` | DMG |
| Windows x64 | `windows-2025` | `mongodesk-windows-x64` | MSI |
| Linux x64 | `ubuntu-24.04` | `mongodesk-linux-x64` | DEB |

Open a completed workflow run under **Actions**, then download the desired installer from **Artifacts**. Packages and test reports are retained for 14 days. Each job installs Java 21, restores the Gradle dependency cache, runs the regression tests, and creates its installer. Test reports are uploaded even if a job fails. Actions are pinned to full commit hashes, and the workflow only requests read access to repository contents.

CI installs official MongoDB Database Tools into the runner's user directory to exercise archive/restore integration tests. Downloads and SHA-256 hashes are pinned in `packaging/database-tools-lock.json`; the installer verifies the checksum before installing. Linux uses Xvfb for desktop-related tests, and Windows configures the runner's WiX 3 tools for JDK 21 MSI packaging.

CI explicitly passes `-PciUnsigned=true` because personal signing identities are not present on hosted runners. CI packages are not Developer ID signed, notarized, or Windows code signed. Normal local macOS builds still require the configured persistent signing identity; the local installer continues to reject ad-hoc builds. No signing secrets are required by this workflow, and version tags create draft releases only after validation and all builds pass. Publication remains manual.


## Versioned releases and updates

The agreed release process is documented in [RELEASING.md](RELEASING.md). `VERSION` is the single source for the app and installer version; the next planned release is **1.1.0**. A matching `v1.1.0` tag, pushed after the exact main commit passes CI, builds all four platforms and creates a draft GitHub Release with changelog notes, versioned installers, SHA-256 checksums, and an Ed25519-signed update manifest. A maintainer reviews and publishes it manually. Published releases are never overwritten by the workflow.

The repository update-signing secret is configured; only its public key is committed. This establishes release delivery for the in-app updater, which still needs to be integrated before the first update-enabled release is tagged. Existing installations do not gain self-update support merely from this release-process change.
