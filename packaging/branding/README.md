# MongoDesk Workspace identity

Approved direction: **01 Workspace** — two document panes forming an M, navy and cobalt on an ice-blue app tile. The wordmark is MongoDesk, with Mongo in navy and Desk in cobalt.

- `workspace-master.png`: approved production raster master, generated with the built-in imagegen tool.
- `concepts.png`: original concept board.
- `GenerateLegacyIcon.swift`: archived renderer for the previous database-cylinder icon.
- Generated assets: `../icons/MongoDesk.png`, `../icons/MongoDesk.icns`, `../icons/MongoDesk.ico`, and `../../src/main/resources/branding/mongodesk-icon.png`.

Regenerate on macOS with `python3 packaging/icons/generate-icons.py`, then rebuild the application. The script only resizes and packages the approved master; it does not redraw it.

Generation prompt: Extract and refine the top-left 01 Workspace concept into one isolated square app icon. Preserve the two angled navy/cobalt document panes, central M notch, three pale lines per pane, pale ice-blue rounded macOS tile, restrained shadow, and transparent exterior. No text, labels, or other concepts.
