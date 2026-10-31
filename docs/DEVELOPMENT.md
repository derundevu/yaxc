# Development

Build, CI and regression coverage for contributors. For installation and everyday use, see the [project overview](../README.md).
Run all commands below from the repository root.

## Build

Use JDK 21, Android SDK platform 36/build-tools 36.0.0, and NDK 28.2.13676358.
Set `JAVA_HOME` and `ANDROID_HOME` to existing installations. The selected core candidate uses **Go 1.27.3**
(`GOTOOLCHAIN=go1.27.3`); gomobile/gobind are pinned through `XrayCore/go.mod` and installed from that locked
module graph. XrayHelper selects the same Go patch. Use the Gradle wrapper.

Native builds use published fork commits pinned by the parent gitlinks; see [Native dependencies](NATIVE.md#sources-and-versions).
Local scripts and CI build those sources directly without applying patches. Do not reset edited submodules to older parent gitlinks.

On a fresh checkout with no local submodule edits:

```bash
git submodule update --init --recursive
```

For an arm64 device, build the native artifacts and debug APK:

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$(go env GOPATH)/bin:$PATH"
./buildXrayCore.sh arm64 &&
./buildXrayHelper.sh arm64 &&
./gradlew -PabiTarget=arm64-v8a :app:assembleDebug
```

For Android-only changes, reuse `app/libs/XrayCore.aar` and the helper binaries under
`app/src/main/assets/xrayhelpers/<android ABI>` only if their sources are unchanged and their ABIs match `abiTarget`.
Each helper build keeps only the selected ABI assets and removes the old flat helper asset. Then run just Gradle.
Native scripts install the pinned gomobile tool and download Go modules; do not use their optional setup/refresh arguments for routine builds.
See [Native dependencies](NATIVE.md) before changing native code or pinned versions.

| Device ABI / `abiTarget`                     | Native argument |
| -------------------------------------------- | --------------- |
| `armeabi-v7a`                                | `arm`           |
| `arm64-v8a`                                  | `arm64`         |
| `x86`                                        | `386`           |
| `x86_64`                                     | `amd64`         |
| `armeabi-v7a,arm64-v8a,x86,x86_64` (default) | `all`           |

For all four ABI APKs plus a complete universal APK:

```bash
./buildXrayCore.sh all && ./buildXrayHelper.sh all && ./gradlew :app:assembleDebug
```

The `all` core build creates one AAR containing all four ABIs; the helper build creates one executable asset
per ABI. Gradle retains ABI splits and adds a universal output whenever `abiTarget` selects multiple ABIs.
The all-ABI debug build produces `app-universal-debug.apk` plus the four `app-<ABI>-debug.apk` files in
`app/build/outputs/apk/debug/`. A single-ABI build produces only its split APK, such as
`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`; explicitly select that ABI when building native artifacts for one device.
Every APK uses the same `app/versionCode.txt` value, currently **1175**, without architecture offsets.
`build_do_not_push.sh` is machine-specific and may install a stale APK after a build failure; do not use it as the build entry point.

### Install and verify

After a successful build, choose `DEVICE_ID` from `adb devices -l`; confirm the ABI with
`adb -s "$DEVICE_ID" shell getprop ro.product.cpu.abi`. Installation and launch are separate from building:

```bash
adb -s "$DEVICE_ID" install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk &&
adb -s "$DEVICE_ID" shell am start -W -n io.github.derundevu.yaxc/.activity.MainActivity
```

Debug and release share `io.github.derundevu.yaxc`. `-r` preserves data only with a compatible signature;
stop on a signing conflict, never uninstall or clear data to bypass it. Do not install an old APK after a failed build.
Observe the actual changed scenario, not just process startup. VPN/root testing must not interrupt the user's connection without approval.
For subscription changes, verify repeat refresh and failure handling without destroying profiles or selection;
for list changes, verify expand/scroll/selection on a large controlled group without changing real subscriptions.

### Local TV and tablet emulators

[`tools/android-emulator.sh`](../tools/android-emulator.sh) manages two dedicated x86_64 AVDs:

| Target   | AVD              | System image                    | Display                                        |
| -------- | ---------------- | ------------------------------- | ---------------------------------------------- |
| `tv`     | `yaxc-tv-36`     | Android TV, API 36, revision 4  | 1920 × 1080, D-pad, no touchscreen             |
| `tablet` | `yaxc-tablet-36` | Google APIs, API 36, revision 7 | Pixel Tablet, 2560 × 1600, multitouch/rotation |

Both use 4 KB pages. The local toolchain pins Emulator **37.2.13**, platform-tools **37.0.1** and
command-line tools **23.0.0**. These images do not include the Play Store; no Google account is needed
to install APKs, use Settings or run instrumentation. The API 35 CI/page-size matrix is unchanged.

Requirements: Linux x86_64, usable `/dev/kvm`, Android CLI (`android`) on `PATH`, and JDK 21 for setup.
The SDK, Android user files and persistent AVD data live in ignored `.android/`, outside Gradle's
`build/` directories. Setup does not replace the system SDK, change shell configuration or overwrite
existing AVDs. It reuses SDK license acceptances from `ANDROID_HOME` when available.

```bash
# Use the existing JDK 21 installation; adjust this path on another machine.
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./tools/android-emulator.sh setup
```

Start each graphical emulator in a separate terminal (X11/XWayland):

```bash
./tools/android-emulator.sh start tv
./tools/android-emulator.sh start tablet
```

For runs without a window, use `start tv --headless` or `start tablet --headless` instead.
Do not launch the same AVD twice. In another terminal, wait for actual Android boot/package readiness:

```bash
./tools/android-emulator.sh wait tv
./tools/android-emulator.sh wait tablet
```

The launcher uses KVM, software OpenGL ES/SwiftShader, disabled Vulkan and a cold boot without
snapshot loading/saving. Installed apps and disk data persist; this is not a factory reset.
This renderer is for functional/UI checks, not host-GPU performance measurements. Host HTTP proxy
settings remain in effect, but loopback addresses bypass the proxy for the emulator process:
proxied local netsim gRPC connections otherwise block QEMU startup and trigger its hang detector.

Use the helper's `adb` command rather than an unqualified `adb` command. It selects the reserved
console port and verifies the AVD name before acting; it never targets a physical device.
After the successful [x86_64 regression build](#isolated-android-instrumentation), install and launch
the isolated app (set `DEVICE=tablet` for the other AVD):

```bash
DEVICE=tv
./tools/android-emulator.sh adb "$DEVICE" install -r app/build/outputs/apk/regression/app-x86_64-regression.apk &&
./tools/android-emulator.sh adb "$DEVICE" install -r app/build/outputs/apk/androidTest/regression/app-regression-androidTest.apk &&
./tools/android-emulator.sh adb "$DEVICE" shell am start -W \
  -n io.github.derundevu.yaxc.regression/io.github.derundevu.yaxc.activity.MainActivity
```

The prepared app APK in `.android/artifacts/` is the **1.5.1** smoke baseline, not proof of the current
1.5.3 candidate. These copies do **not** update automatically. Rebuild/reinstall after application
changes; never use old copies after a failed build or emulator-native artifacts for an arm64 phone build.

For manual TV checks, use **Extended controls → Directional Pad**, keyboard arrows/confirmation/Back,
or `./tools/android-emulator.sh adb tv shell input keyevent KEYCODE_DPAD_DOWN`. Do not use mouse clicks on
the content as proof of remote-only navigation. On the tablet, use the rotate controls to check both
orientations. UI Automator hierarchy dumps and `exec-out screencap -p` work on both targets.

Run the existing isolated native/import/Room/VPN instrumentation on the selected AVD:

```bash
PAGE_SIZE="$(./tools/android-emulator.sh adb "$DEVICE" shell getconf PAGE_SIZE)"
./tools/android-emulator.sh adb "$DEVICE" shell am instrument -w -r \
  -e allowVpn true -e expectedPageSize "$PAGE_SIZE" \
  io.github.derundevu.yaxc.regression.test/io.github.derundevu.yaxc.testing.RegressionTestRunner
```

Require the final `OK (...)` result, not just the shell exit code. These tests use disposable synthetic
fixtures and the `.regression` package; do not put real subscriptions or accounts in these AVDs.
For Gradle-managed runs after building matching native artifacts, set `ANDROID_SERIAL` to the result
of `./tools/android-emulator.sh adb "$DEVICE" get-serialno` before `connectedRegressionAndroidTest`.

Stop with the helper so guest filesystem writes are synchronized before the VM exits:

```bash
./tools/android-emulator.sh stop tv
./tools/android-emulator.sh stop tablet
```

## CI and releases

[CI](../.github/workflows/ci.yml) runs on every branch push, pull request and manual dispatch.
It validates workflows with pinned actionlint and inline ShellCheck, and checks tracked shell scripts with `bash -n`.
The Android job first runs host Go compatibility/cancellation regressions against the local forks, then builds
the pinned native dependencies for `arm64-v8a`, assembles the debug APK and runs full `lintDebug` plus
`testDebugUnitTest`. It needs no release secrets and publishes no release.
Lint/test reports and the successful debug APK are retained as workflow artifacts for seven days.

After building the arm64 native artifacts, the local Android check is:

```bash
./gradlew -PabiTarget=arm64-v8a --continue :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

`testDebugUnitTest` runs the host regression suite: real Room/repositories and reconstructed migrations,
loopback HTTP, profile/config saves, import/config/routing transformations, service/ping ownership and
native-graphics Compose/EditorKit interactions. Pure helpers use a plain `Application`; application-backed
tests use `RegressionApplication`, which deliberately skips production startup scheduling. Every Room fixture
owns its database/executor/preferences and resets AndroidX's cached application factory before reuse.
Host tests never load the Android Go library, modify a device or depend on public network services.

Core routing editor tests inject a separate `StandardTestDispatcher` sharing Main's test scheduler.
This queues snapshot processing until the test yields, so concurrent-draft checks do not depend on
host thread timing. `CoroutineStart.UNDISPATCHED` alone does not guarantee suspension at `withContext`:
short work on `Dispatchers.Default` can finish without suspending its caller.

The device CI matrix builds x86_64 dependencies and runs the separate `regression` variant on disposable
API 35 emulators with 4096-byte and 16384-byte pages. Emulator 36.6.11 and the emulator action revision are pinned.
The ordinary debug unit-test task remains enabled independently of the instrumentation build type.
Lint errors and test failures fail CI; warnings remain visible. There is no lint baseline or ignored failure.
Required-check branch rules must be configured separately on GitHub; CI alone does not block direct pushes.

[Release](../.github/workflows/release.yml) remains tag-only (`v*`) and requires the tag to match `versionName`
in `app/build.gradle.kts`. Its five-row matrix builds four signed ABI APKs and a complete universal APK;
the universal row builds all native ABIs but stages only `app-universal-release.apk`. A GitHub Release is published
only after all five builds succeed. Notes come from `metadata/en-US/changelogs/<versionCode>.txt`, using the shared code directly.
Assets are named `yaxc-<tag>-<ABI|universal>.apk`, without a numeric version-code suffix. Choose universal when unsure.
The legacy container wrapper `build-yaxc.sh` follows the same naming: use `NATIVE_ARCH=all` and
`ABI_TARGET=armeabi-v7a,arm64-v8a,x86,x86_64` to stage its universal output, or select one matching native/Android ABI pair.

### Isolated Android instrumentation

The `regression` build uses application ID `io.github.derundevu.yaxc.regression`, its own data and worker
processes, and the debug signing configuration. It never replaces the installed release/debug application.
The test runner refuses any other target package. The original package ID and Room schema version remain unchanged.
Only this variant allows cleartext HTTP to loopback fixture endpoints; production network security is unchanged.

Use a dedicated API 35 x86_64 emulator, not the owner's device. `default` is the 4 KB image;
`google_apis_ps16k` is the 16 KB image. Set `EMU_DEVICE` to that emulator and verify its ABI/page size:

```bash
adb -s "$EMU_DEVICE" shell getprop ro.product.cpu.abi
adb -s "$EMU_DEVICE" shell getconf PAGE_SIZE
bash -e ./buildXrayCore.sh amd64 &&
bash -e ./buildXrayHelper.sh amd64 &&
./gradlew -PabiTarget=x86_64 :app:assembleRegression :app:assembleRegressionAndroidTest
```

Install only after that build succeeds; installation and execution are separate actions:

```bash
adb -s "$EMU_DEVICE" install -r app/build/outputs/apk/regression/app-x86_64-regression.apk &&
adb -s "$EMU_DEVICE" install -r app/build/outputs/apk/androidTest/regression/app-regression-androidTest.apk
```

```bash
PAGE_SIZE=$(adb -s "$EMU_DEVICE" shell getconf PAGE_SIZE)
adb -s "$EMU_DEVICE" shell am instrument -w -r \
  -e allowVpn true -e expectedPageSize "$PAGE_SIZE" \
  io.github.derundevu.yaxc.regression.test/io.github.derundevu.yaxc.testing.RegressionTestRunner
```

Require the final `OK (...)` result and no failed tests: `am instrument` can exit successfully while tests fail.
CI uses `connectedRegressionAndroidTest`, whose provider interprets instrumentation failures correctly.
VPN tests additionally require `allowVpn=true`, emulator hardware and no existing regression-package session.
Their client runs under a separate shell UID; only a synthetic benchmark destination/port reaches the loopback peer.
After each START/forced RESTART, before opening a shell TCP client, the fixture waits for
`ConnectivityManager` to publish an IPv4 default route for a **new VPN network owned by the regression
package**. Service START acknowledges native ownership, not Android's asynchronous route installation.
The previous network cannot satisfy the restart barrier. A core-only NEW_CONFIG keeps the existing TUN
and must not wait for a replacement network. Because the app itself is excluded, callback registration
temporarily adopts shell `NETWORK_SETTINGS` permission to observe other-UID networks; that permission
is then dropped and the callback is always unregistered.

The HTTP fixture keeps netcat's stdin open until it reads the `Content-Length`-framed response body.
Stdin EOF can close the socket before the peer responds. Conversely, UiAutomation can retain stdout's
pipe until its stdin-forwarding thread exits, hiding netcat's exit even after `-w`/`-W` expires.
Both HTTP and chained-stream readers therefore use one monotonic deadline for the entire response,
including partial headers/bodies, and close both pipes on failure. A held-writer partial-frame regression
covers this bound without relying on transport EOF or emulator scheduling.
No root mode, public proxy, real subscription or owner VPN is used. Rebuild matching arm64 native artifacts
before returning to the normal phone build; do not reuse the emulator AAR/helper for another ABI.

Room exports the current [v9 schema](../app/schemas/io.github.derundevu.yaxc.database.YaxcDatabase/9.json).
Migration fixtures v1–v8 are reconstructed from migration SQL and opened through real Room schema validation;
they are not claimed to be historical exported snapshots.

Profile saves retain inserted identities and update content without restoring a stale list index. A target deleted
while its editor is open is rejected instead of reporting a successful no-op; the unsaved draft remains visible.

Subscription refresh uses conditional `NEW_CONFIG`: the service compares exact generated Xray JSON and immutable,
mode-specific platform options against the last successful runtime snapshot. An unchanged candidate updates the
notification/tile label through `STATUS` without validation, file writes or a reconnect event. Names, quotas,
timestamps and unselected profiles are not runtime inputs. Stored SOCKS ports/credentials are reused, not regenerated.
Changed core settings reload Xray once; changed TUN/root options require a full validated restart. Explicit `RESTART`
remains forced. Validation runs outside the ownership lock and Room transaction; the candidate is re-read from
committed state before applying it so edits/deletions during validation cannot publish stale state. Missing selection
stops the owned runtime. A partially committed, cancelled refresh batch still schedules one conditional reconciliation;
that command cannot start an inactive VPN or undo STOP.

Foreground promotion for explicit START/RESTART happens on Android's main thread before consulting native state.
Native teardown runs on the command dispatcher; its successful completion returns to the main thread before STOP
is acknowledged. A start-generation guard rejects stale teardown, and `stopSelfResult` checks the latest delivered
start ID against ActivityManager's pending requests. Foreground is removed only when the stop is accepted, so a
START already queued in Android cannot be demoted or stopped by an older command. An idle STATUS/NETWORK_UPDATE
can finish a deferred stop; a refused native join retains its descriptor, core and foreground notification.

### Connection failure recovery

The service publishes validation and native-start failures to process-local `ConnectionFailureState`; the main
screen observes it independently of running/stopped status. Failures survive Activity recreation and service
teardown, but are not persisted to disk. Explicit dismissal, retry, a new connection command or successful start
clears them. A stale dialog callback cannot clear a newer failure. Cancellation does not publish a failure.

The initial foreground notification is posted before asynchronous validation. Reload/restart validates the candidate
before stopping the active core; a rejected candidate leaves it running. Native start diagnostics are checked.
Standard GeoIP/GeoSite errors identify missing, damaged, unreadable or missing-group data. An ambiguous native EOF
is checked against local top-level protobuf entry framing on IO; the app does not decode or load the full database.
Unknown and custom-file errors keep their native diagnostic without a misleading standard-download action.

The themed dialog opens Resources at the relevant card without dismissing the failure or starting a download.
Returning leaves an explicit Retry action; generic failures show diagnostics, while geodata diagnostics expand on
request so long native errors do not hide the actionable explanation at large font sizes.

Permanent native-graphics Compose/Robolectric tests exercise the actual main/resources surfaces. Main recovery runs
in all four explicit themes at 1.5x fonts, including error replacement/restoration, expanded diagnostics,
Resources/Retry/Dismiss and focused scrolling.
A throwaway canvas exporter visually confirmed collapsed/expanded dialogs on a short screen; its runner was removed.
The Android suite independently checks native missing/corrupt external geodata and recovery after installing a
synthetic file. This does not replace approved arm64 installed-device UI recovery; the owner's app and VPN were untouched.

### JSON code editor

Profiles, global config sections and routing JSON share one full-height BlackSquircle editor. Profile name/source
are in **Profile details**; routing's existing visual mode remains separate. The editor owns its IME/navigation
insets and clips native drawing to the code viewport, rather than nesting code inside a scrolling card.

The toolbar provides undo/redo, literal find/replace and explicit formatting. JSON syntax is checked automatically
after edits; there is no separate check button. Search ignores case
unless **Match case** is enabled. The symbol row supports punctuation and indent/outdent; the options menu controls
font size and line wrapping. A red syntax-error status reports its line/column; tapping it moves the caret to the error.
Syntax checking is not Xray validation: profile Save still checks the generated configuration with the native core.
The profile help button explains the editor controls, saving/Xray validation and draft lifetime in English/Russian.
Its text body supports scrolling when the guide exceeds the dialog viewport.

Parsing, searching and formatting run off Main. Formatting changes only JSON whitespace, preserving member order,
unknown fields, duplicate keys, numeric spellings and escapes. Saving global sections or routing JSON does not
silently reformat the source. Each document has its own raw draft, selection, viewport and undo history; ViewModels
retain these and host metadata through configuration recreation, without retaining Views or Activities.
Drafts are not durable across process death or closing the editor.

The `AndroidView` holder is keyed by editor instance: unkeyed reuse sends a new section's edits to old listeners.
Palette changes refresh native highlighting. A no-copy `SpanWatcher` also invalidates syntax-span changes because
EditorKit's syntax span lacks `UpdateAppearance`; otherwise Android can keep drawing the previous theme's colors.
Code and gutter paints must share the same SP size. Set the initial font before attaching `LineNumbersPlugin`, and
explicitly update its paints on later font changes. The two-argument TextView setter bypasses EditorKit, while
EditorKit's own SP setter can skip plugin notifications after TextView invalidates its layout. Let the plugin measure
the resulting digit width; do not compensate with a fixed gutter width or disable viewport clipping.

Device smoke exercised real key input, formatting/undo/redo, located errors, case-insensitive search and single/all
replacement, indentation, font size/wrapping, profile details, and actual dark/light rendering. A 500-row raw profile
retained text, selection, both scroll axes, metadata and undo/redo through Activity recreation. Global sections retained
separate invalid drafts/selection through switching and recreation; array fragments formatted correctly. Routing kept
raw source and unknown fields through unchanged JSON/visual roundtrips and recreation.
Gutter smoke additionally verified 99/100, 999/1000 and 10,000 logical lines, 10/14/24 sp fonts, horizontal scrolling,
growth/shrinkage and recreation at the large font. Both selected and normal label glyphs stayed inside the viewport.
Help/navigation smoke verified the updated guide opens/closes without changing the selection, Save remains and the
duplicate check action is absent. Automatic diagnostics left the caret alone; tapping the red status scrolled to an
error on line 182 and selected its exact offset. The automatic valid status and remaining Format action also passed.
The temporary instrumentation was not a permanent suite; no Save, profile/subscription replacement or VPN/root toggle
was exercised. These checks do not establish process-death recovery, all IMEs/font scales or native routing correctness.

### Large subscription lists and ping

Subscription headers, profile rows and footers are separately keyed items in one `LazyColumn`. A viewport-sized
glass surface preserves one continuous expanded card without a subscription-height layer. The surface and list
share one remembered platform overscroll effect: the outer container renders it and the list handles events
through `withoutVisualEffect()`. This keeps the glass attached to rows during Android edge stretch without
disabling the native behavior. Source dragging temporarily collapses the expanded body, then restores it on drop/cancel.
Profile summaries/server labels are parsed off the UI thread only when the database profile snapshot changes,
not on each ping result. Index normalization reads the current global order and writes changed indices in one
transaction; collectors await it instead of launching overlapping per-row update jobs.

Ping uses five non-exported worker processes because Xray has process-global dialer/logger state. Each process
runs one core; a batch uses at most five coroutine loops, not one coroutine per profile. The bounded worker pool
also limits concurrent helper callers. Cancellation retains its slot/config/binding until native teardown;
cancelled queued acquisition returns any undelivered slot. Pings never switch the selected profile or reload the VPN,
and operation IDs reject stale results/completion. Rebuild the AAR only after native source changes;
see [native contracts](NATIVE.md#ping-worker).

Native regressions, using this checkout's local forks:

```bash
(cd XrayCore && go test -mod=readonly -count=1 -timeout=90s ./...)
(cd XrayCore && go test -mod=readonly -race -count=1 -timeout=90s .)
(cd XrayCore && go test -mod=readonly -count=1 -timeout=30s github.com/xtls/libxray/nodep)
```

For repeated randomized native teardown/cancellation checks with the race detector:

```bash
(cd XrayCore && go test -mod=readonly -race -count=3 -shuffle=on -timeout=120s .)
```

Historical 26.10.21 migration verification: all four core AARs, helper binaries, JNI libraries and debug APKs were rebuilt with Go 1.27.3, pinned gomobile
`dcd2a3258864`, NDK 28.2.13676358 and JDK 21. Full lint and 47 JVM tests pass, including six validation IPC
tests: zero failures/errors/skips; lint has 0 errors, 124 warnings and 2 hints. Every APK passes
`zipalign -c -P 16 4`, contains its matching ABI/helper and the exact NDK-stripped output of the rebuilt Go AAR,
and has no temporary smoke manifest/DEX entrypoint. Core/JNI LOAD alignment and RELRO ends pass 16 KB checks.
The unchanged graphics-path prebuilt still has strict RELRO-end remainders (8 KiB, or 12 KiB on x86_64);
actual 16 KB-device loading is not established. The available device reports 4096-byte pages.

Historical 1.4.0 migration artifacts and source/toolchain/hash provenance remain under
`app/build/migration-20261029/` (ignored); do not relabel those APKs as 1.5.0.
The current **1.5.3 / Xray 26.10.29** release procedure is above. Published notes through 1.5.2 are unchanged.
The fork commits and parent gitlinks are already recorded in `9d86576`. Review/publish the new release files
and require hosted CI for that parent revision. Local verification is not release signing, tagging or publication.

These commands exercise the public app facade and local HTTP/SOCKS targets, not a device VPN. The facade suite
must remain non-parallel because core dialer/logger/environment state is process-global.

Manual arm64 smoke covered 1,000 synthetic profiles at top/middle/bottom, dark/light card continuity,
selection/actions, collapse/re-expand and reorder/drop/cancel. A temporary composition probe observed 7–13
settled active rows and at most 20 during transitions, not 1,000. Real Room normalization converged for
1,000 profiles; ViewModel replacement/progress and 1,000 result updates were exercised on-device.
Loopback native smoke covered GET/HEAD/TCP, 1-second timeout and a successful response after 5.5 seconds with
a 7-second timeout. The five-worker device smoke admitted exactly five simultaneous real target requests in
five distinct processes, bounded ten queued pings, cancelled 15 waiting and five active requests, verified their
connections closed, and restored all five slots while a main-process Xray sentinel kept routing.
The real Activity completed 12 successful delayed loopback pings in waves of five/five/two.
Held-pointer screenshots verified the glass and rows stretch together at both ends of a 1,000-profile list.
This is not an end-to-end VPN/root or real-subscription network guarantee.
