#!/bin/bash

set -euo pipefail

PATCH_FILE="$(pwd)/app/src/main/jni/patches/hev-socks5-tun0-defense.patch"
REPO_DIR="app/src/main/jni/hev-socks5-tunnel"

if [[ ! -f "$PATCH_FILE" ]]; then
  exit 0
fi

echo "Apply hev-socks5-tunnel local patch"

if git -C "$REPO_DIR" apply --check "$PATCH_FILE" >/dev/null 2>&1; then
  git -C "$REPO_DIR" apply "$PATCH_FILE"
  exit 0
fi

if git -C "$REPO_DIR" apply --reverse --check "$PATCH_FILE" >/dev/null 2>&1; then
  echo "hev-socks5-tunnel patch already applied"
  exit 0
fi

echo "Failed to apply hev-socks5-tunnel patch: $PATCH_FILE"
exit 1
