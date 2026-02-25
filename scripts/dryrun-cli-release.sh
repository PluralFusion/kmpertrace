#!/usr/bin/env sh
set -eu

usage() {
  cat <<'USAGE'
Run a local CLI release dry run using act, then validate installer flow.

Usage:
  scripts/dryrun-cli-release.sh [--tag <cli-vX.Y.Z>]

Defaults:
  --tag  cli-v0.0.0-dryrun.<timestamp>
USAGE
}

die() {
  echo "[dryrun-cli-release] $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "Need either sha256sum or shasum"
  fi
}

TAG="cli-v0.0.0-dryrun.$(date +%Y%m%d%H%M%S)"
ACT_ARCH="${KMPERTRACE_ACT_CONTAINER_ARCH:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --tag)
      [ "$#" -ge 2 ] || die "Missing value for --tag"
      TAG="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

case "$TAG" in
  cli-v*) ;;
  *) die "Tag must start with cli-v (got: $TAG)" ;;
esac

VERSION="${TAG#cli-v}"

need_cmd act
need_cmd awk
need_cmd mktemp
need_cmd cat

echo "[dryrun-cli-release] Running act for tag: $TAG"
if [ -n "$ACT_ARCH" ]; then
  act --bind --container-architecture "$ACT_ARCH" workflow_dispatch -W .github/workflows/publish.yml -j publish-cli --input ref="$TAG"
else
  act --bind workflow_dispatch -W .github/workflows/publish.yml -j publish-cli --input ref="$TAG"
fi

ASSET_DIR="build/act-release-assets/$TAG"
[ -d "$ASSET_DIR" ] || die "Expected ACT asset directory not found: $ASSET_DIR"

ZIP_FILE="$ASSET_DIR/kmpertrace-cli-$VERSION.zip"
TAR_FILE="$ASSET_DIR/kmpertrace-cli-$VERSION.tar"
SUMS_FILE="$ASSET_DIR/SHA256SUMS"
INSTALL_SH="$ASSET_DIR/install.sh"
INSTALL_PS1="$ASSET_DIR/install.ps1"

[ -f "$ZIP_FILE" ] || die "Missing asset: $ZIP_FILE"
[ -f "$TAR_FILE" ] || die "Missing asset: $TAR_FILE"
[ -f "$SUMS_FILE" ] || die "Missing asset: $SUMS_FILE"
[ -f "$INSTALL_SH" ] || die "Missing asset: $INSTALL_SH"
[ -f "$INSTALL_PS1" ] || die "Missing asset: $INSTALL_PS1"

expected_zip="$(awk -v name="$(basename "$ZIP_FILE")" '$NF==name {print $1; exit}' "$SUMS_FILE")"
expected_tar="$(awk -v name="$(basename "$TAR_FILE")" '$NF==name {print $1; exit}' "$SUMS_FILE")"
[ -n "$expected_zip" ] || die "No checksum entry for $(basename "$ZIP_FILE")"
[ -n "$expected_tar" ] || die "No checksum entry for $(basename "$TAR_FILE")"

actual_zip="$(sha256_file "$ZIP_FILE")"
actual_tar="$(sha256_file "$TAR_FILE")"
[ "$expected_zip" = "$actual_zip" ] || die "ZIP checksum mismatch"
[ "$expected_tar" = "$actual_tar" ] || die "TAR checksum mismatch"

echo "[dryrun-cli-release] Checksums verified"

echo "[dryrun-cli-release] Testing Unix installer via piped one-liner"
INSTALL_TMP="$(mktemp -d "${TMPDIR:-/tmp}/kmpertrace-install.XXXXXX")"
INSTALL_ROOT="$INSTALL_TMP/root"
BIN_DIR="$INSTALL_TMP/bin"
BASE_URL="file://$(cd "$ASSET_DIR" && pwd)"

cat "$INSTALL_SH" | sh -s -- \
  --version "$VERSION" \
  --base-url "$BASE_URL" \
  --install-root "$INSTALL_ROOT" \
  --bin-dir "$BIN_DIR"

"$BIN_DIR/kmpertrace-cli" --help >/dev/null

echo "[dryrun-cli-release] CLI install test passed"
echo "[dryrun-cli-release] Assets: $ASSET_DIR"
if command -v pwsh >/dev/null 2>&1 || command -v powershell >/dev/null 2>&1; then
  echo "[dryrun-cli-release] PowerShell runtime detected: run install.ps1 validation manually if needed"
else
  echo "[dryrun-cli-release] PowerShell runtime not found; skipped install.ps1 execution"
fi
