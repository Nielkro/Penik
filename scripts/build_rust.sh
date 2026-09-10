#!/usr/bin/env bash
set -euo pipefail

# ── Colors & Logging ──
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log()  { echo -e "${BLUE}[build_rust]${NC} $*"; }
info() { echo -e "${GREEN}[build_rust]${NC} $*"; }
warn() { echo -e "${YELLOW}[build_rust] WARNING:${NC} $*"; }
err()  { echo -e "${RED}[build_rust] ERROR:${NC} $*" >&2; exit 1; }

# Locate script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUST_DIR="${ROOT_DIR}/rust/penik-crypto"
JNI_LIBS_DIR="${ROOT_DIR}/android/app/src/main/jniLibs"

log "Project root: ${ROOT_DIR}"
log "Rust crate:   ${RUST_DIR}"

# ── Locate Android NDK ──
NDK_PATH="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${NDK_HOME:-/opt/android-ndk}}}"
if [[ ! -d "${NDK_PATH}" ]]; then
  # Try searching common paths in Android SDK
  if [[ -d "${ANDROID_HOME:-/opt/android-sdk}/ndk" ]]; then
    NDK_PATH="$(find "${ANDROID_HOME:-/opt/android-sdk}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  fi
fi

if [[ ! -d "${NDK_PATH}" ]]; then
  err "Android NDK not found. Please set ANDROID_NDK_ROOT or install NDK to /opt/android-ndk"
fi

NDK_BIN="${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -d "${NDK_BIN}" ]]; then
  err "NDK toolchain bin directory not found at: ${NDK_BIN}"
fi

info "Using Android NDK at: ${NDK_PATH}"

# Android API level for NDK compilers (minSdk is 24)
API_LEVEL=24

# Target definitions: <rust-target>:<clang-binary>:<jni-folder>
TARGETS=(
  "aarch64-linux-android:aarch64-linux-android${API_LEVEL}-clang:arm64-v8a"
  "armv7-linux-androideabi:armv7a-linux-androideabi${API_LEVEL}-clang:armeabi-v7a"
  "x86_64-linux-android:x86_64-linux-android${API_LEVEL}-clang:x86_64"
  "i686-linux-android:i686-linux-android${API_LEVEL}-clang:x86"
)

# ── Build Android JNI Libraries ──
mkdir -p "${JNI_LIBS_DIR}"

for entry in "${TARGETS[@]}"; do
  IFS=':' read -r rust_target clang_bin jni_dir <<< "${entry}"
  
  CLANG_PATH="${NDK_BIN}/${clang_bin}"
  if [[ ! -x "${CLANG_PATH}" ]]; then
    err "Clang compiler not found at: ${CLANG_PATH}"
  fi
  
  DEST_DIR="${JNI_LIBS_DIR}/${jni_dir}"
  mkdir -p "${DEST_DIR}"
  
  log "Compiling ${rust_target} (-> ${jni_dir}/libpenik_crypto.so)..."
  
  # Target env variables
  TARGET_ENV_LINKER="CARGO_TARGET_$(echo "${rust_target}" | tr '[:lower:]' '[:upper:]' | tr '-' '_')_LINKER"
  TARGET_ENV_CC="CC_$(echo "${rust_target}" | tr '-' '_')"
  
  export "${TARGET_ENV_LINKER}=${CLANG_PATH}"
  export "${TARGET_ENV_CC}=${CLANG_PATH}"
  
  (
    cd "${RUST_DIR}"
    cargo build --release --target "${rust_target}"
  )
  
  SRC_SO="${RUST_DIR}/target/${rust_target}/release/libpenik_crypto.so"
  if [[ ! -f "${SRC_SO}" ]]; then
    err "Built library not found at: ${SRC_SO}"
  fi
  
  cp "${SRC_SO}" "${DEST_DIR}/libpenik_crypto.so"
  info "✓ Copied to ${DEST_DIR}/libpenik_crypto.so ($(du -h "${DEST_DIR}/libpenik_crypto.so" | cut -f1))"
done

# ── Build WASM (Optional if wasm-pack is installed) ──
if command -v wasm-pack &>/dev/null; then
  log "wasm-pack found. Building WebAssembly package for client..."
  WASM_OUT="${ROOT_DIR}/client/pkg/penik-crypto-wasm"
  (
    cd "${RUST_DIR}"
    wasm-pack build --target web --out-dir "${WASM_OUT}"
  )
  info "✓ Built WASM package to ${WASM_OUT}"
else
  warn "wasm-pack not found in PATH; skipping WASM build (prebuilt wasm in client/ remains active)"
fi

info "🎉 All Rust targets built and deployed successfully!"
