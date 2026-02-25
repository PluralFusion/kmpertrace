#!/usr/bin/env sh
set -eu

REPO="pluralfusion/kmpertrace"
VERSION="latest"
INSTALL_ROOT="${KMPERTRACE_INSTALL_ROOT:-$HOME/.local/opt}"
BIN_DIR="${KMPERTRACE_BIN_DIR:-$HOME/.local/bin}"
BASE_URL_OVERRIDE="${KMPERTRACE_RELEASE_BASE_URL:-}"

usage() {
  cat <<'USAGE'
Install kmpertrace-cli from GitHub Releases.

Usage:
  install.sh [--version <x.y.z|cli-vx.y.z|latest>] [--install-root <dir>] [--bin-dir <dir>] [--base-url <url>]

Defaults:
  --version latest
  --install-root ~/.local/opt
  --bin-dir ~/.local/bin

Examples:
  install.sh
  install.sh --version 0.1.0
  install.sh --version cli-v0.1.0
USAGE
}

die() {
  echo "[kmpertrace-cli installer] $*" >&2
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
    die "Need either 'sha256sum' or 'shasum' to verify checksums"
  fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "Missing value for --version"
      VERSION="$2"
      shift 2
      ;;
    --install-root)
      [ "$#" -ge 2 ] || die "Missing value for --install-root"
      INSTALL_ROOT="$2"
      shift 2
      ;;
    --bin-dir)
      [ "$#" -ge 2 ] || die "Missing value for --bin-dir"
      BIN_DIR="$2"
      shift 2
      ;;
    --base-url)
      [ "$#" -ge 2 ] || die "Missing value for --base-url"
      BASE_URL_OVERRIDE="$2"
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

need_cmd curl
need_cmd tar
need_cmd awk
need_cmd mktemp

resolve_tag() {
  if [ -n "$BASE_URL_OVERRIDE" ] && [ "$VERSION" = "latest" ]; then
    die "--base-url requires an explicit --version"
  fi

  if [ "$VERSION" = "latest" ]; then
    releases_json=$(curl -fsSL \
      -H "Accept: application/vnd.github+json" \
      -H "User-Agent: kmpertrace-cli-installer" \
      "https://api.github.com/repos/$REPO/releases?per_page=100") || die "Failed to query GitHub releases"

    tag=$(printf '%s' "$releases_json" | awk '
      match($0, /"tag_name"[[:space:]]*:[[:space:]]*"cli-v[^"]*"/) {
        s = substr($0, RSTART, RLENGTH)
        sub(/^.*"tag_name"[[:space:]]*:[[:space:]]*"/, "", s)
        sub(/"$/, "", s)
        print s
        exit
      }
    ')
    [ -n "$tag" ] || die "Could not find a cli-v* release tag"
    printf '%s' "$tag"
    return
  fi

  case "$VERSION" in
    cli-v*) printf '%s' "$VERSION" ;;
    *) printf 'cli-v%s' "$VERSION" ;;
  esac
}

TAG="$(resolve_tag)"
CLI_VERSION="${TAG#cli-v}"
ARCHIVE="kmpertrace-cli-${CLI_VERSION}.tar"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kmpertrace-cli-install.XXXXXX")"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

BASE_URL="https://github.com/$REPO/releases/download/$TAG"
if [ -n "$BASE_URL_OVERRIDE" ]; then
  BASE_URL="$BASE_URL_OVERRIDE"
fi
ARCHIVE_PATH="$TMP_DIR/$ARCHIVE"
SUMS_PATH="$TMP_DIR/SHA256SUMS"

echo "[kmpertrace-cli installer] Installing tag $TAG"
curl -fL "$BASE_URL/$ARCHIVE" -o "$ARCHIVE_PATH" || die "Failed to download $ARCHIVE"
curl -fL "$BASE_URL/SHA256SUMS" -o "$SUMS_PATH" || die "Failed to download SHA256SUMS"

EXPECTED_SHA="$(awk -v name="$ARCHIVE" '
  $0 ~ "^[0-9a-fA-F]{64}[[:space:]]+\\*?" name "$" {
    print $1
    exit
  }
' "$SUMS_PATH")"
[ -n "$EXPECTED_SHA" ] || die "No checksum entry found for $ARCHIVE"

ACTUAL_SHA="$(sha256_file "$ARCHIVE_PATH")"
[ "$EXPECTED_SHA" = "$ACTUAL_SHA" ] || die "Checksum mismatch for $ARCHIVE"

TARGET_DIR="$INSTALL_ROOT/kmpertrace-cli-$CLI_VERSION"
mkdir -p "$INSTALL_ROOT" "$BIN_DIR"
rm -rf "$TARGET_DIR"
tar -xf "$ARCHIVE_PATH" -C "$INSTALL_ROOT"

ln -sfn "$TARGET_DIR/bin/kmpertrace-cli" "$BIN_DIR/kmpertrace-cli"

if command -v java >/dev/null 2>&1; then
  JAVA_NOTICE="java detected"
else
  JAVA_NOTICE="java not detected (kmpertrace-cli needs Java 17+)"
fi

echo "[kmpertrace-cli installer] Installed to: $TARGET_DIR"
echo "[kmpertrace-cli installer] Launcher: $BIN_DIR/kmpertrace-cli"
echo "[kmpertrace-cli installer] $JAVA_NOTICE"

case ":$PATH:" in
  *":$BIN_DIR:"*)
    echo "[kmpertrace-cli installer] Run: kmpertrace-cli --help"
    ;;
  *)
    echo "[kmpertrace-cli installer] Add to PATH: export PATH=\"$BIN_DIR:\$PATH\""
    echo "[kmpertrace-cli installer] Then run: kmpertrace-cli --help"
    ;;
esac
