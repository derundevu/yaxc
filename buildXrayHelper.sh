#!/bin/bash
set -e

TARGET="$1"
ARCHS=(arm arm64 386 amd64)
DEST="../app/src/main/assets/xrayhelpers"

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

prepare_go() {
  echo "Install dependencies"
  go mod download
}

build_android() {
  echo "Building XrayHelper for $TARGET"
  local targets=("$TARGET")
  if [[ "$TARGET" == "all" ]]; then
    targets=("${ARCHS[@]}")
  fi

  # Remove the legacy flat asset and any previously generated variants.
  rm -rf "../app/src/main/assets/xrayhelper" "$DEST"
  mkdir -p "$DEST"
  local arch abi
  for arch in "${targets[@]}"; do
    case "$arch" in
      arm) abi="armeabi-v7a" ;;
      arm64) abi="arm64-v8a" ;;
      386) abi="x86" ;;
      amd64) abi="x86_64" ;;
    esac
    CGO_ENABLED=0 GOOS=linux GOARCH="$arch" \
      go build -v -o "$DEST/$abi" \
      -ldflags "-s -w -buildid=" -buildvcs=false -trimpath ./main
  done
}

check_target

pushd XrayHelper
prepare_go
build_android
popd
