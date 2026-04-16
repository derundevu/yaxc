#!/bin/bash

TARGET="$1"
REFRESH="$2"
SETUP="$3"
ARCHS=(arm arm64 386 amd64)
DEST="../app/libs"
PATCH_DIR="patches"

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

apply_local_patches() {
  local patch_file="$(pwd)/$PATCH_DIR/libxray-xray-core-26.3.27.patch"
  if [[ ! -f "$patch_file" ]]; then
    return 0
  fi

  echo "Apply local compatibility patches"
  if git -C libXray apply --check "$patch_file" >/dev/null 2>&1; then
    git -C libXray apply "$patch_file"
    return 0
  fi

  if git -C libXray apply --reverse --check "$patch_file" >/dev/null 2>&1; then
    echo "libXray compatibility patch already applied"
    return 0
  fi

  echo "Failed to apply libXray compatibility patch: $patch_file"
  exit 1
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
  local VERSION=$(awk -F ' ' '/golang.org\/x\/mobile/ {print $2}' go.mod)
  go install golang.org/x/mobile/cmd/gomobile@$VERSION
  go mod download
}

build_android() {
  echo "Building XrayCore for $TARGET"
  rm -f "$DEST/XrayCore*"
  gomobile init
  gomobile bind -o "$DEST/XrayCore.aar" -androidapi 26 -target "android/$TARGET" -ldflags="-buildid=" -trimpath
}

refresh_dependencies() {
  echo "Gradle: refresh dependencies"
  ./gradlew --refresh-dependencies clean
}

check_target

pushd XrayCore
apply_local_patches
prepare_go
build_android
popd

if [[ -n "$REFRESH" ]]; then
  refresh_dependencies
fi
