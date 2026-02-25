# KmperTrace Release Checklist (Runtime + CLI)

This repo has two independent release lanes driven by tag prefixes:
- Runtime/framework lane (`vX.Y.Z`): publishes `kmpertrace-runtime` to Maven Central and uploads `KmperTraceRuntime.xcframework.zip`.
- CLI lane (`cli-vX.Y.Z`): uploads JVM CLI distributions (`.zip`, `.tar`) plus `SHA256SUMS`.
- Both lanes also upload installer assets: `install.sh` and `install.ps1`.

## Tag conventions
- Runtime/framework release tag: `v<version>` (example: `v0.3.2`).
- CLI release tag: `cli-v<version>` (example: `cli-v0.1.0`).
- The `publish.yml` workflow routes by tag prefix:
  - `v*` (excluding `cli-v*`) -> runtime lane.
  - `cli-v*` -> CLI lane.

## Runtime release steps (Maven + SwiftPM)
1) Bump runtime version
   - Edit `gradle.properties` `kmpertraceVersion=...` (example: `0.3.2` -> `0.3.3`).
   - Commit and push to `main`.

2) Compute SwiftPM checksum for XCFramework zip
   - GitHub -> Actions -> **Compute SPM XCFramework checksum** -> Run workflow.
   - Use workflow from `main`.
   - Fill "Version hint" with `<version>` for URL suggestion in logs.

3) Update `Package.swift`
   - Copy checksum + suggested URL (`.../releases/download/v<version>/KmperTraceRuntime.xcframework.zip`).
   - Update root `Package.swift` and commit.

4) Tag and release
   - Tag: `git tag v<version>` and `git push origin v<version>`.
   - Create GitHub Release for that tag.

5) CI actions on runtime tag
   - `publish.yml` publishes runtime artifacts to Maven Central.
   - `publish.yml` builds/zips XCFramework and verifies checksum vs `Package.swift`.
   - `publish.yml` uploads `KmperTraceRuntime.xcframework.zip`, `install.sh`, and `install.ps1` to the release assets.

## CLI release steps (distribution files)
1) Choose CLI version
   - No runtime version bump is required for CLI-only releases.

2) Tag and release
   - Tag: `git tag cli-v<version>` and `git push origin cli-v<version>`.
   - Create GitHub Release for that tag.

3) CI actions on CLI tag
   - `publish.yml` runs CLI lane only.
   - Builds `:kmpertrace-cli:distZip` and `:kmpertrace-cli:distTar` with `-PkmpertraceVersion=<version>` parsed from `cli-v<version>`.
   - Uploads:
     - `kmpertrace-cli-<version>.zip`
     - `kmpertrace-cli-<version>.tar`
     - `SHA256SUMS`
     - `install.sh`
     - `install.ps1`

## Manual workflow dispatch
- `publish.yml` also supports manual `workflow_dispatch`.
- Set `ref` to an existing tag:
  - Runtime: `v<version>`
  - CLI: `cli-v<version>`
- The workflow validates tag format and fails fast on invalid values.

## Local dry run with act (CLI lane)
- Use:
  ```bash
  ./scripts/dryrun-cli-release.sh
  ```
- What it does:
  - Runs `publish.yml` `publish-cli` job via `act` with a temporary `cli-v...` tag.
  - Confirms generated assets and SHA256 checksums.
  - Uses a local `file://` base URL for installer validation (no HTTP server needed).
  - Executes the Unix installer one-liner against local assets and verifies `kmpertrace-cli --help`.
- Dry-run outputs are saved under:
  - `build/act-release-assets/<cli-tag>/`

## Installer one-liners for users
- macOS/Linux:
  ```bash
  curl -fsSL https://github.com/pluralfusion/kmpertrace/releases/latest/download/install.sh | sh
  ```
- Windows (PowerShell):
  ```powershell
  iwr https://github.com/pluralfusion/kmpertrace/releases/latest/download/install.ps1 -UseBasicParsing | iex
  ```

## SwiftPM consumers
- Add package dependency:
  ```swift
  .package(url: "https://github.com/pluralfusion/kmpertrace.git", from: "<version>")
  ```
- SwiftPM reads `Package.swift` from the release tag and verifies the binary checksum.

## Notes / Gotchas
- The commit a tag points to must already contain the final `Package.swift` for runtime releases.
- Do not use Actions artifact URLs in `Package.swift`; use GitHub Release asset URLs.
- Runtime and CLI versions are intentionally independent; keep changelog/release notes clear about which lane a tag belongs to.
