#!/bin/bash
set -e

TARGET="$1"
REFRESH="$2"
SETUP="$3"
ARCHS=(arm arm64 386 amd64)
DEST="../app/libs"
# The pinned libcronet.a archives are built with R_AARCH64_PLT32 (314) and
# R_AARCH64_GOTPCREL32 (315) relocations. LLD 18 (NDK r27) rejects the latter
# with "unknown relocation (315)"; LLD 19 (NDK r28) resolves both.
NDK_MINIMUM_LLD=19

is_in_array() {
  local value="$1"
  local array=("${@:2}")

  for item in "${array[@]}"; do
    if [[ "$item" == "$value" ]]; then
      return 0
    fi
  done

  return 1
}

check_target() {
  if [[ "$TARGET" != "all" ]] && ! is_in_array "$TARGET" "${ARCHS[@]}"; then
    echo "Not supported"
    exit 1
  fi
}

ndk_revision() {
  sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$1/source.properties" 2>/dev/null | head -n 1
}

# Explicit ANDROID_NDK_HOME wins; otherwise use the newest side-by-side NDK,
# the same choice gomobile makes on its own.
resolve_ndk() {
  if [[ -n "$ANDROID_NDK_HOME" ]]; then
    echo "$ANDROID_NDK_HOME"
    return 0
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  local best="" best_revision="" dir revision
  [[ -n "$sdk" ]] || return 0
  for dir in "$sdk"/ndk/*; do
    [[ -d "$dir" ]] || continue
    revision=$(ndk_revision "$dir")
    [[ -n "$revision" ]] || continue
    if [[ -z "$best" || "$revision" > "$best_revision" ]]; then
      best="$dir"
      best_revision="$revision"
    fi
  done
  printf '%s' "$best"
}

# gomobile links the core with the NDK it finds and ignores this choice's
# linker version, so make the requirement explicit before the link fails deep
# inside gobind.
check_ndk() {
  local ndk lld lld_major revision
  ndk=$(resolve_ndk)
  if [[ -z "$ndk" || ! -d "$ndk" ]]; then
    echo "No usable Android NDK: set ANDROID_NDK_HOME or install NDK 28.2.13676358 under \$ANDROID_HOME/ndk" >&2
    exit 1
  fi
  lld=$(ls "$ndk"/toolchains/llvm/prebuilt/*/bin/ld.lld 2>/dev/null | head -n 1)
  if [[ -z "$lld" ]]; then
    echo "No ld.lld found under $ndk" >&2
    exit 1
  fi
  lld_major=$("$lld" --version | sed -n 's/^LLD \([0-9][0-9]*\).*/\1/p')
  revision=$(ndk_revision "$ndk")
  if [[ -z "$lld_major" || "$lld_major" -lt "$NDK_MINIMUM_LLD" ]]; then
    echo "NDK ${revision:-unknown} at $ndk ships LLD ${lld_major:-unknown}; LLD $NDK_MINIMUM_LLD or newer (NDK 28.2.13676358) is required for the pinned Cronet archive" >&2
    exit 1
  fi
  export ANDROID_NDK_HOME="$ndk"
  echo "Using NDK ${revision:-unknown} (LLD $lld_major) from $ndk"
}

prepare_go() {
  echo "Install dependencies"
  if [[ -n "$SETUP" ]]; then
    rm go*
    go mod init XrayCore
    go mod edit -replace github.com/xtls/xray-core=./Xray-core
    go mod edit -replace github.com/xtls/libxray=./libXray
    go mod tidy
    go get golang.org/x/mobile
    go get google.golang.org/genproto
  fi
  go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
  go mod download
}

build_android() {
  echo "Building XrayCore for $TARGET"
  local targets="android/$TARGET"
  if [[ "$TARGET" == "all" ]]; then
    targets="android/arm,android/arm64,android/386,android/amd64"
  fi
  rm -f "$DEST/XrayCore*"
  # Both tools are already installed from the locked module graph above.
  # gomobile init would reinstall gobind@latest; bind needs no OpenAL setup.
  # Android links the pinned Cronet archive statically; with_purego is for host smoke runs.
  # Match upstream Android builds: anet requires access to net.zoneCache.
  gomobile bind -o "$DEST/XrayCore.aar" -androidapi 26 -target "$targets" -tags=with_naive_outbound -ldflags="-buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" -trimpath
}

refresh_dependencies() {
  echo "Gradle: refresh dependencies"
  ./gradlew --refresh-dependencies clean
}

check_target
check_ndk

pushd XrayCore
prepare_go
build_android
popd

if [[ -n "$REFRESH" ]]; then
  refresh_dependencies
fi
