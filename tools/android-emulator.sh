#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.android"
SDK="$STATE/sdk"
HOST_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export ANDROID_AVD_HOME="$STATE/avd" ANDROID_USER_HOME="$STATE/user"
export ANDROID_EMULATOR_HOME="$STATE/user"
ADB="$SDK/platform-tools/adb"

usage() {
    cat <<'USAGE'
Usage:
  tools/android-emulator.sh setup
  tools/android-emulator.sh start tv|tablet [--headless] [emulator options...]
  tools/android-emulator.sh wait tv|tablet
  tools/android-emulator.sh adb tv|tablet <adb command...>
  tools/android-emulator.sh stop tv|tablet

Persistent SDK, AVDs and Android user files live in .android/ (gitignored).
Start runs in the foreground; use another terminal for wait, adb and tests.
TV uses console port 5560; tablet uses 5562. No physical device is targeted.
Setup requires Android CLI (android) and JDK 21 on JAVA_HOME/PATH.
USAGE
}

select_device() {
    case "${1:-}" in
        tv) avd=yaxc-tv-36; port=5560 ;;
        tablet) avd=yaxc-tablet-36; port=5562 ;;
        *) usage >&2; exit 2 ;;
    esac
    serial="emulator-$port"
}

require_device() {
    local name
    name="$("$ADB" -s "$serial" emu avd name | tr -d '\r')"
    name="${name%%$'\n'*}"
    if [[ "$name" != "$avd" ]]; then
        printf 'Refusing device on port %s: expected AVD %s, got %s\n' "$port" "$avd" "$name" >&2
        exit 1
    fi
}

create_avd() {
    local name="$1" image="$2" profile="$3"
    if [[ ! -f "$ANDROID_AVD_HOME/$name.ini" ]]; then
        printf 'no\n' | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd --name "$name" --package "$image" \
            --device "$profile" --path "$ANDROID_AVD_HOME/$name.avd"
    fi
}

command="${1:-help}"
if [[ $# -gt 0 ]]; then shift; fi
case "$command" in
    setup)
        mkdir -p "$SDK/licenses" "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
        if [[ -n "$HOST_SDK" && "$HOST_SDK" != "$SDK" && -d "$HOST_SDK/licenses" ]]; then
            cp -a "$HOST_SDK/licenses/." "$SDK/licenses/"
        fi
        android --sdk="$SDK" sdk install cmdline-tools/latest@23.0.0
        android --sdk="$SDK" sdk install emulator@37.2.13
        android --sdk="$SDK" sdk install platform-tools@37.0.1
        android --sdk="$SDK" sdk install system-images/android-36/android-tv/x86_64@4.0.0
        android --sdk="$SDK" sdk install system-images/android-36/google_apis/x86_64@7.0.0
        create_avd yaxc-tv-36 'system-images;android-36;android-tv;x86_64' tv_1080p
        create_avd yaxc-tablet-36 'system-images;android-36;google_apis;x86_64' pixel_tablet
        "$SDK/emulator/emulator" -accel-check
        ;;
    start)
        select_device "${1:-}"
        shift
        headless=()
        if [[ "${1:-}" == --headless ]]; then
            headless=(-no-window)
            shift
        fi
        if [[ ! -f "$ANDROID_AVD_HOME/$avd.ini" ]]; then
            printf 'Missing %s; run %s setup first.\n' "$avd" "$0" >&2
            exit 1
        fi
        # Cold boot keeps app data but avoids stale snapshots after image/APK changes.
        # Software GLES avoids dependence on the host GPU/Wayland driver stack.
        export QT_QPA_PLATFORM=xcb
        # A proxied localhost gRPC connection to netsim deadlocks QEMU startup.
        export no_proxy="${no_proxy:+$no_proxy,}${NO_PROXY:+$NO_PROXY,}localhost,127.0.0.1,::1"
        export NO_PROXY="$no_proxy"
        export no_grpc_proxy="${no_grpc_proxy:+$no_grpc_proxy,}$no_proxy"
        exec "$SDK/emulator/emulator" -avd "$avd" -port "$port" \
            -accel on -cores 4 -memory 3072 \
            -gpu swiftshader -feature -Vulkan -no-snapshot -no-boot-anim \
            "${headless[@]}" "$@"
        ;;
    wait)
        select_device "${1:-}"
        deadline=$((SECONDS + 240))
        timeout 240 "$ADB" -s "$serial" wait-for-device
        require_device
        until [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do
            if (( SECONDS >= deadline )); then
                printf 'Android boot timed out for %s.\n' "$avd" >&2
                exit 1
            fi
            sleep 1
        done
        "$ADB" -s "$serial" shell cmd package path android
        printf '%s ready (ADB: %s).\n' "$avd" "$serial"
        ;;
    adb)
        select_device "${1:-}"
        shift
        require_device
        exec "$ADB" -s "$serial" "$@"
        ;;
    stop)
        select_device "${1:-}"
        require_device
        "$ADB" -s "$serial" shell sync
        exec "$ADB" -s "$serial" emu kill
        ;;
    help|-h|--help) usage ;;
    *) usage >&2; exit 2 ;;
esac
