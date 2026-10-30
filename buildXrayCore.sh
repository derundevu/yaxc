#!/bin/bash
set -e

TARGET="$1"
REFRESH="$2"
SETUP="$3"
ARCHS=(arm arm64 386 amd64)
DEST="../app/libs"

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
  if ! is_in_array "$TARGET" "${ARCHS[@]}"; then
    echo "Not supported"
    exit 1
  fi
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
  rm -f "$DEST/XrayCore*"
  # Both tools are already installed from the locked module graph above.
  # gomobile init would reinstall gobind@latest; bind needs no OpenAL setup.
  # Match upstream Android builds: anet requires access to net.zoneCache.
  gomobile bind -o "$DEST/XrayCore.aar" -androidapi 26 -target "android/$TARGET" -ldflags="-buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" -trimpath
}

refresh_dependencies() {
  echo "Gradle: refresh dependencies"
  ./gradlew --refresh-dependencies clean
}

check_target

pushd XrayCore
prepare_go
build_android
popd

if [[ -n "$REFRESH" ]]; then
  refresh_dependencies
fi
