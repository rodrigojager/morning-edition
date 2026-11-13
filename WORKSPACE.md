# Product map: morning-edition

One row per folder (and relevant subfolder) of this repository. Keep it portable: no absolute paths, disks or local-only folders.

| Path | What lives there |
|---|---|
| `src/` | source code |
| `assets/` | final assets used by the product |
| `tests/` | automated tests |
| `scripts/` | scripts the product needs (build, production deploy) |

## Allowed Markdown files
- `README.md`
- `AGENTS.md` (root, and folders with their own rules, up to the second level)
- `WORKSPACE.md` (root only)

AI working artifacts live outside this repository, in the container's `_work/` folder.
