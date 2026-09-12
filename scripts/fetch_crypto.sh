#!/usr/bin/env bash
set -euo pipefail

# ── Colors & Logging ──
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log()  { echo -e "${BLUE}[fetch_crypto]${NC} $*"; }
info() { echo -e "${GREEN}[fetch_crypto]${NC} $*"; }
warn() { echo -e "${YELLOW}[fetch_crypto] WARNING:${NC} $*"; }
err()  { echo -e "${RED}[fetch_crypto] ERROR:${NC} $*" >&2; exit 1; }

REPO="${GITHUB_REPO:-Nielkro/Penik}"
TOKEN="${GITHUB_TOKEN:-}"
RELEASE_TAG="${CRYPTO_RELEASE_TAG:-crypto-latest}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

FETCH_WASM=0
FETCH_ANDROID=0
FORCE=0
WASM_DIR="${ROOT_DIR}/client/pkg/penik-crypto-wasm"
ANDROID_DIR="${ROOT_DIR}/android/app/src/main/jniLibs"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f|--force)
      FORCE=1
      ;;
    --wasm)
      FETCH_WASM=1
      if [[ $# -gt 1 && ! "$2" =~ ^-- ]]; then
        WASM_DIR="$2"
        shift
      fi
      ;;
    --android)
      FETCH_ANDROID=1
      if [[ $# -gt 1 && ! "$2" =~ ^-- ]]; then
        ANDROID_DIR="$2"
        shift
      fi
      ;;
    --all)
      FETCH_WASM=1
      FETCH_ANDROID=1
      ;;
    -h|--help)
      echo "Usage: $0 [--wasm [path]] [--android [path]] [--all] [--force]"
      exit 0
      ;;
    *)
      err "Unknown option: $1"
      ;;
  esac
  shift
done

# If no specific target given, fetch both
if [[ ${FETCH_WASM} -eq 0 && ${FETCH_ANDROID} -eq 0 ]]; then
  FETCH_WASM=1
  FETCH_ANDROID=1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

# ── Helper: Download Artifact from Actions API ──
download_actions_artifact() {
  local artifact_name="$1"
  local output_zip="$2"

  if [[ -z "${TOKEN}" ]]; then
    return 1
  fi

  log "Querying GitHub Actions API for artifact '${artifact_name}' in ${REPO}..."
  local api_url="https://api.github.com/repos/${REPO}/actions/artifacts?per_page=50"
  local resp
  resp="$(curl -sSL -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/vnd.github+json" "${api_url}" || true)"

  local download_url
  download_url="$(echo "${resp}" | jq -r --arg name "${artifact_name}" '.artifacts[] | select(.name == $name and .expired == false) | .archive_download_url' 2>/dev/null | head -n 1 || true)"

  if [[ -n "${download_url}" && "${download_url}" != "null" ]]; then
    log "Downloading artifact '${artifact_name}' from Actions run..."
    if curl -sSLf -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/vnd.github+json" "${download_url}" -o "${output_zip}"; then
      info "✓ Downloaded Actions artifact '${artifact_name}'"
      return 0
    fi
  fi
  warn "Artifact '${artifact_name}' not found or download failed via Actions API"
  return 1
}

# ── Helper: Download from GitHub Releases ──
download_release_file() {
  local filename="$1"
  local output_file="$2"

  local release_url="https://github.com/${REPO}/releases/download/${RELEASE_TAG}/${filename}"
  log "Attempting to download from release '${RELEASE_TAG}': ${filename}..."
  if curl -sSLf "${release_url}" -o "${output_file}"; then
    info "✓ Downloaded ${filename} from release '${RELEASE_TAG}'"
    return 0
  fi
  warn "Could not download ${filename} from release '${RELEASE_TAG}'"
  return 1
}

# ── Fetch WASM ──
if [[ ${FETCH_WASM} -eq 1 ]]; then
  mkdir -p "${WASM_DIR}"
  if [[ ${FORCE} -eq 0 && -f "${WASM_DIR}/penik_crypto_bg.wasm" && -s "${WASM_DIR}/penik_crypto_bg.wasm" ]]; then
    info "WASM binary already exists at ${WASM_DIR}/penik_crypto_bg.wasm, skipping download (use --force to overwrite)."
  else
    log "Fetching penik-crypto WASM package..."
    wasm_zip="${TMP_DIR}/wasm.zip"
    wasm_success=0

    # 1. Try Actions API with token
    if download_actions_artifact "penik-crypto-wasm" "${wasm_zip}"; then
      unzip -q -o "${wasm_zip}" -d "${WASM_DIR}"
      wasm_success=1
    fi

    # 2. Try release zip
    if [[ ${wasm_success} -eq 0 ]] && download_release_file "penik-crypto-wasm.zip" "${wasm_zip}"; then
      unzip -q -o "${wasm_zip}" -d "${WASM_DIR}"
      wasm_success=1
    fi

    # 3. Try direct release .wasm file
    if [[ ${wasm_success} -eq 0 ]] && download_release_file "penik_crypto_bg.wasm" "${WASM_DIR}/penik_crypto_bg.wasm"; then
      wasm_success=1
    fi

    if [[ ${wasm_success} -eq 1 && -f "${WASM_DIR}/penik_crypto_bg.wasm" ]]; then
      info "✓ WASM successfully deployed to ${WASM_DIR}/penik_crypto_bg.wasm ($(du -h "${WASM_DIR}/penik_crypto_bg.wasm" | cut -f1))"
    else
      err "Failed to acquire penik_crypto_bg.wasm from GitHub Actions or release assets."
    fi
  fi
fi

# ── Fetch Android .so ──
if [[ ${FETCH_ANDROID} -eq 1 ]]; then
  mkdir -p "${ANDROID_DIR}"
  if [[ ${FORCE} -eq 0 && -f "${ANDROID_DIR}/arm64-v8a/libpenik_crypto.so" && -s "${ANDROID_DIR}/arm64-v8a/libpenik_crypto.so" ]]; then
    info "Android JNI libraries already exist at ${ANDROID_DIR}, skipping download (use --force to overwrite)."
  else
    log "Fetching Android JNI libraries (.so)..."
    android_zip="${TMP_DIR}/android.zip"
    android_success=0

    # 1. Try Actions API with token
    if download_actions_artifact "penik-crypto-android" "${android_zip}"; then
      unzip -q -o "${android_zip}" -d "${ANDROID_DIR}"
      android_success=1
    fi

    # 2. Try release zip
    if [[ ${android_success} -eq 0 ]] && download_release_file "penik-crypto-android.zip" "${android_zip}"; then
      unzip -q -o "${android_zip}" -d "${ANDROID_DIR}"
      android_success=1
    fi

    if [[ ${android_success} -eq 1 && -f "${ANDROID_DIR}/arm64-v8a/libpenik_crypto.so" ]]; then
      info "✓ Android JNI libraries successfully deployed to ${ANDROID_DIR}"
    else
      err "Failed to acquire Android JNI libraries from GitHub Actions or release assets."
    fi
  fi
fi

info "Done!"
