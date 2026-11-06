# Development

Build, CI and regression coverage for contributors. For installation and everyday use, see the [project overview](../README.md).
Run all commands below from the repository root.

## Build

Use JDK 21, Android SDK platform 36/build-tools 36.0.0, and NDK 28.2.13676358.
Set `JAVA_HOME` and `ANDROID_HOME` to existing installations. The selected core candidate uses **Go 1.27.3**
(`GOTOOLCHAIN=go1.27.3`); gomobile/gobind are pinned through `XrayCore/go.mod` and installed from that locked
module graph. XrayHelper selects the same Go patch. Use the Gradle wrapper.
If `ANDROID_SDK_ROOT` is already set, make it match `ANDROID_HOME` (or unset it for the command); AGP rejects conflicting SDK paths.

Native builds use submodule sources directly; see [Native dependencies](NATIVE.md#sources-and-versions).
The parent pins published 26.10.31 core/wrapper and Live Routing tunnel forks; see the exact commits in
[Native dependencies](NATIVE.md#sources-and-versions). Hosted CI passed on `518e3a9`; later uncommitted work is
not covered by that run. Local scripts and CI apply no patches; preserve any local submodule edits.

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

`:app:preBuild` runs `:app:verifyNativeArtifacts` before packaging. Every ABI in `abiTarget` must have
`jni/<ABI>/libgojni.so` inside `app/libs/XrayCore.aar` and a nonempty matching helper asset. A missing ABI fails the
build with rebuild instructions rather than producing an APK that crashes at startup. After an emulator-only build,
rebuild for the phone ABI explicitly, or rebuild both native artifacts with `all` before using the default ABI set.

For dependency migrations, use the project skill
[yaxc-native-upgrade](../.omp/skills/yaxc-native-upgrade/SKILL.md), invoked as `/skill:yaxc-native-upgrade`.
It covers dirty recursive forks, upstream/security comparisons, module/schema alignment, rebuilt ABI sets,
isolated release gates and the owner publication handoff; current code and instructions remain the source of truth.

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
Every APK uses the same `app/versionCode.txt` value, currently **1176**, without architecture offsets.
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
1.5.4 candidate. These copies do **not** update automatically. Rebuild/reinstall after application
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
The Android job first runs facade/library ownership, live-routing, Observatory/router/outbound, TLS/XHTTP and
splice race regressions plus retained-wrapper compatibility checks. It then builds the pinned native dependencies
for `arm64-v8a`, assembles the debug APK and runs full `lintDebug` plus `testDebugUnitTest`.
TLS/ECH tests use local certificate/OCSP/DNS/HTTPS fixtures, not public service availability.
The job needs no release secrets and publishes no release.
Lint/test reports and the successful debug APK are retained as workflow artifacts for seven days.

The router gate reads `XrayCore/Xray-core/resources/geosite.dat`. CI downloads test data from a pinned
`XTLS/Xray-rules-dat` revision before running the native tests; it does not rely on an existing cache.
Before running the router tests locally, prepare the same fixture from the repository root:

```bash
mkdir -p XrayCore/Xray-core/resources
curl --fail --location --silent --show-error \
  --output XrayCore/Xray-core/resources/geosite.dat \
  https://raw.githubusercontent.com/XTLS/Xray-rules-dat/b2fd201b17cdbb2a6aff5e49a52910247d026778/geosite/geosite-expand.dat
```

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

If Robolectric cannot fetch its SDK 28 fixture because `repo1.maven.org` fails TLS, a one-off invocation can use
`JAVA_TOOL_OPTIONS=-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2 ./gradlew ...`
with the same task/ABI arguments. This fetched the fixture during Live Routing work; it is not a permanent
repository, Gradle or global environment change.

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
The prepared candidate is **1.5.4 / versionCode 1176**, with notes in
[`metadata/en-US/changelogs/1176.txt`](../metadata/en-US/changelogs/1176.txt). Local unsigned APKs are packaging
checks, not production-signed release assets; publication still requires the configured release signing secrets.
The release workflow does not run or wait for the CI workflow. Tag only the exact parent revision whose clean-checkout
CI has passed with all modified fork commits published and pinned; pushing a release tag starts publication directly.

Assets are named `yaxc-<tag>-<ABI|universal>.apk`, without a numeric version-code suffix. Choose universal when unsure.
The legacy container wrapper `build-yaxc.sh` follows the same naming: use `NATIVE_ARCH=all` and
`ABI_TARGET=armeabi-v7a,arm64-v8a,x86,x86_64` to stage its universal output, or select one matching native/Android ABI pair.

### Isolated Android instrumentation

The `regression` build uses application ID `io.github.derundevu.yaxc.regression`, its own data and worker
processes, and the debug signing configuration. It never replaces the installed release/debug application.
The test runner refuses any other target package. The original package ID is unchanged; regression uses the same Room schema as production.
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

Room exports the current [v10 schema](../app/schemas/io.github.derundevu.yaxc.database.YaxcDatabase/10.json).
Migration fixtures v1–v9 are reconstructed from migration SQL and opened through real Room schema validation;
they are not claimed to be historical exported snapshots.

Migration 9→10 adds nullable `links.refresh_interval_minutes`; existing rows use the global interval. The optional
subscription form value is minutes (minimum 15); blank means `Settings.refreshLinksIntervalMinutes`. Provider
metadata remains display-only and cannot override either choice. On-open and WorkManager due checks share this
precedence; manual refresh bypasses due time. One network-constrained 15-minute worker remains, not one timer per
subscription. Host regressions cover independent schedules, global changes after clearing an override, metadata
refresh/stale-edit preservation, real form restoration/offline saves and every migration path through v10.

Manual subscription refresh retains its result in `LinksManagerViewModel` until **Close**, including across activity
recreation; a restored process never replays a potentially committed request. Added/removed/modified names are
computed against the transaction's stored profiles and published only for successful sources. Matching preserves
duplicate occurrences: exact name/content pairs first, then same-name modifications, then unchanged-content renames.
JSON object-key order, whitespace and numeric spelling are ignored; array order and exact numeric values matter.
Unmatched profiles become additions/removals. Reports do not edit Live Routing membership.
Background refresh and source-form saves skip report calculation, but every refresh uses the same identity matching.
Errors are never presented as a successful unchanged refresh.

Persistent identity is source-scoped: exact name/semantic-config occurrences first, then unambiguous unchanged-config
renames, then config edits whose unchanged name occurs exactly once in both complete source snapshots. Ambiguous
duplicates and replacements receive fresh IDs; removed IDs remain explicit missing Live Routing members. Retained
profiles keep user/global ordering, while existing DAO insert/remove operations maintain dense indices. Matching uses
transaction-local profiles, not the pre-network snapshot; content updates never restore stale indices. No schema migration.


Profile saves retain inserted identities and update content without restoring a stale list index. A target deleted
while its editor is open is rejected instead of reporting a successful no-op; the unsaved draft remains visible.

Subscription refresh uses conditional `NEW_CONFIG`: the service compares generated Xray JSON, the installed-package
identity snapshot and immutable, mode-specific platform options against the last successful runtime snapshot.
An unchanged candidate updates notification/tile metadata through `STATUS` without validation, file writes or a
reconnect event. Names, quotas and timestamps alone are not core inputs; unselected profiles matter only when used
by groups in the selected Live Routing connection. Stored SOCKS ports/credentials are reused, not regenerated.
Validated routing/rule/balancer/outbound-graph changes use `ApplyRouting` when the shared core settings and platform
options are unchanged. Other core changes reload Xray once; changed TUN/root options require a full validated
restart. Explicit `RESTART` remains forced. A rejected live update never falls back to a restart.
Validation runs outside the ownership lock and Room transaction; committed state is re-read before applying it so
edits/deletions during validation cannot publish stale state. Missing selection on `NEW_CONFIG` stops the owned
runtime; explicit live apply rejects it without stopping the last applied session. A partially committed, cancelled
refresh batch still schedules one conditional reconciliation; that command cannot start an inactive VPN or undo STOP.

Foreground promotion for explicit START/RESTART happens on Android's main thread before consulting native state.
Native teardown runs on the command dispatcher; its successful completion returns to the main thread before STOP
is acknowledged. A start-generation guard rejects stale teardown, and `stopSelfResult` checks the latest delivered
start ID against ActivityManager's pending requests. Foreground is removed only when the stop is accepted, so a
START already queued in Android cannot be demoted or stopped by an older command. An idle STATUS/NETWORK_UPDATE
can finish a deferred stop; a refused native join retains its descriptor, core and foreground notification.

### Live Routing

**Routing → Live Routing** edits one persisted plan of cross-subscription proxy groups, a default-group choice and
ordered app/domain rules; see [user configuration and limitations](../README.md#live-routing). The compiler composes
namespaced outbound graphs, not independent full profiles. The special **Connect → Live Routing** group persists
`LiveRoutingHelper.CONNECTION_PROFILE_ID` (`-1`) as the selected connection, without adding a Room profile or source.
Only `selectedProfile == LiveRoutingHelper.CONNECTION_PROFILE_ID` reads and compiles the saved Live Routing plan.
Ordinary profile connections use the selected profile with global/Core Routing settings and never read, validate
or apply Live Routing groups or rules. Only positive profile IDs are checked against Room for ordinary selection
invalidation. The main screen observes saved plan changes, preserves this selection across recreation and retains
lazy subscription rows and reordering.

Core Routing and Live Routing share `RoutingReorderState`: stable-key preview order, `animateItem` neighbour
placement and one draft commit on drop. Expanded bodies compact during dragging and restore without height
transitions. The final viewport anchor is requested in a composition `SideEffect`, after bodies/order are restored;
requesting it from the pointer callback can remeasure old compact contents and briefly discard the active row.
Absolute viewport-top compensation then spring-settles any legitimate list-end clamping. Cancellation and
busy/key changes restore authoritative order without committing stale indices. Live header presses share a
rounded whole-card indication source; fields and switches remain independently interactive.

`CoreRoutingReorderTest` checks preview/cancel/busy semantics and unequal-height release frames;
`LiveRoutingEditorInteractionTest` exercises actual expanded-group drops and rule cancellation/section boundaries.


Live Routing connections start from global runtime settings, never an arbitrary member's full configuration.
`defaultGroupId` is optional in the plan: absent/null uses the first group; an explicit missing ID is rejected.
At least one group is required for a Live Routing connection. The default group's Select/Random/round-robin strategy
handles the final fallback, live **Default route** targets and Core Routing/DNS proxy destinations. Core direct/block
rules retain precedence over the fallback. All members stay loaded for default/member/strategy changes. The first
enabled matching live rule precedes Core Routing; app and domain fields are ANDed, with OR within each list.
Missing members or unsupported graphs reject Live Routing start/apply, never silently substitute a profile. They do
not block ordinary profile connections, which remain usable without editing or deleting the saved plan.
These changes do not alter the Room schema.

Save validates/persists the local plan and dispatches `APPLY_ROUTING` only for an active Live Routing session,
never an ordinary profile session. Persistence and runtime acceptance are distinct: errors are surfaced through
Connect and retain the previous runtime. The service requires unchanged shared core/platform settings, validates
in the isolated worker, re-reads committed state and
then calls the native atomic update. New flows use the accepted rules; existing streams keep their handlers, with
no core/TUN restart or selected-profile change. Incompatible settings, observed-handler changes or the native
512-version retention budget require an explicit reconnect, never an automatic one.

App criteria require Android 10+ VPN owner lookup and authenticated SOCKS identities. Shared UIDs are indistinguishable,
unknown owners do not app-match, and Apps Routing exclusions bypass these rules. Domain-only rules also work in
SOCKS-only mode. Root transparent mode rejects the Live Routing connection explicitly; a saved plan does not affect
ordinary root connections. Availability checks remain an optional per-group filter for Select/Random/round robin;
LeastPing requires them. Existing observatory-based native balancers retain their separate compatibility limits in
[the native contract](NATIVE.md#live-routing-candidate-2026-11-01).

`checkAvailability` is a strict optional boolean, default false. LeastPing without it is invalid; the editor enables it
when choosing that strategy. Health-enabled Select follows configured priority; Random/round robin include every
eligible member. LeastPing selects one eligible member with the lowest last successful nonnegative delay, breaking
ties in configured order. One transient failure retains its successful latency while existing hysteresis decides eligibility.
The compiler loads all member graphs, emits startup-blackhole balancers and `_yaxcLiveHealth` metadata, and uses the
same group target for rules, independent defaults, Default route and Core Routing proxy targets.

`Settings.liveRoutingHealthIntervalSeconds` is persisted separately from the plan, defaults to 10 and supports 5–3600.
The Basic settings Live Routing section validates before writing any settings and retains unsaved input across recreation.
Ping Address/interval changes hot-apply for health-enabled Live Routing sessions; metadata is excluded from restart
keys. The compiler emits integer `intervalSeconds`; the strict native parser rejects invalid values before runtime
publication. Native options
are immutable per plan; an accepted interval update reschedules from each last probe start without canceling matching probes.

The native controller reuses Observatory exact-outbound HTTP GET/2xx with bounded cancellation, not direct TCP probes.
Starts are immediate, then per configured interval when one of four workers is available, with a fixed 5s timeout.
Initial success admits, two failures remove, three successes restore; observations expire at `3 * interval + timeout`.
At the default interval, unqueued detection/recovery is about 10–25s/20–35s, plus up to 1s UI polling.
Controller error/no eligible member chooses an explicit blackhole. Health transitions only publish candidates:
no Xray/TUN restart, request retry or established-flow migration.

The editor's **?** action beside Save opens the bilingual guide rather than occupying the main list. Runtime labels
require a matching saved group and source fingerprints. Source reordering preserves logical profile IDs; deleted or
ambiguously changed members remain missing instead of being reassigned. Manual refresh can hot-apply immediately,
so stop first when inspection must precede use. Missing members/invalid graphs remain validation failures, not
network-health states; a rejected candidate can leave Room newer than the runtime.

With JDK 21 and matching native artifacts, run targeted host regressions (choose the ABI matching the artifacts):
Prepare the router geodata fixture using the commands in [CI and releases](#ci-and-releases) before the Go checks.

```bash
./gradlew -PabiTarget=x86_64 :app:testDebugUnitTest \
  --tests 'io.github.derundevu.yaxc.helper.LiveRoutingHelperTest' \
  --tests 'io.github.derundevu.yaxc.viewmodel.LiveRoutingViewModelTest' \
  --tests 'io.github.derundevu.yaxc.presentation.main.MainScreenRoomTest' \
  --tests 'io.github.derundevu.yaxc.service.TProxyServiceLifecycleTest'
(cd XrayCore && GOTOOLCHAIN=go1.27.3 go test -mod=readonly -race -count=1 -timeout=240s . ./lib)
(cd XrayCore && GOTOOLCHAIN=go1.27.3 go test -mod=readonly -race -count=1 -timeout=240s \
  github.com/xtls/xray-core/app/observatory github.com/xtls/xray-core/app/router \
  github.com/xtls/xray-core/app/proxyman/outbound github.com/xtls/xray-core/proxy/socks \
  github.com/xtls/xray-core/core github.com/xtls/xray-core/common/session \
  github.com/xtls/xray-core/transport/internet github.com/xtls/xray-core/transport/internet/tls \
  github.com/xtls/xray-core/transport/internet/splithttp)
(cd XrayCore && GOTOOLCHAIN=go1.27.3 go test -mod=readonly -count=1 -timeout=90s \
  github.com/xtls/libxray/nodep github.com/xtls/libxray/share)
```

For device coverage, rebuild the matching x86_64 AAR/JNI and regression APKs using the isolated commands above.
Add `-e class io.github.derundevu.yaxc.VpnDeviceIntegrationTest` to the instrumentation invocation to target VPN
ownership/live-flow cases; require the final all-passing result, not merely installation or shell success.
`LiveRoutingAvailabilityIntegrationTest` adds production-cadence synthetic-peer VPN scenarios: startup backup,
failure/recovery thresholds, all-down isolation, same-ID endpoint replacement, least-ping winner changes and supported
5→8-second interval hot apply. Exact response recipients, retained framed TCP and unchanged TUN are checked across
independent/default/rule/Core Routing proxy targets. Fixtures restore only their own state and reject an existing emulator
VPN. Native regressions also exercise 3600→5→3600 intervals, expiry, in-flight retention, tie priority, concurrency,
cancellation and continuous new traffic during healthy hot applies.

The 2026-11-06 JDK 21/all-ABI check passed **414 JVM tests**, with no failures/errors/skips.
Debug lint reported **0 errors, 141 warnings, 2 hints**. Current facade/library Go race tests passed.
Five API 36 x86_64 / 4 KB scenarios exercised HTTP identity updates, actual Russian settings/strategy UI,
draft recreation/invalid bounds, real least-ping recipients, live intervals, retained TCP and unchanged TUN.
Release lint and physical/root/16 KB runtime verification were not rerun for these changes.
The ViewModel fixture cancels its recurring poller inside the test body before virtual-time auto-drain; `@After`
alone is too late for an independently owned recurring coroutine.

Historical published .31 migration verification (predating the current routing changes): source-matched AAR/helpers
and debug/regression/unsigned-release APKs covered all four ABIs.
Go race gates include facade/library, wrapper, Observatory, router, outbound, core/protocol, TLS/XHTTP,
configuration, sniffing and geodata. Automatic synthetic peers must not block on an undrained probe-event queue;
manual probe events remain reliable. The full library suite retains continuous traffic through 200 hot applies.
Final API 35 x86_64 guests at measured **4096** and **16384** byte pages each passed **28/28** cases.
An actual-activity smoke displayed **26.10.31** and navigated Routing → Live Routing → Help → Back.
Local emulator 37.2.13 is not the CI-pinned 36.6.11. Fifteen APKs passed ABI/helper/version and ZIP alignment checks;
release outputs are unsigned, not production-signing proof. See the [published source pins](NATIVE.md#sources-and-versions).

Earlier .29 coverage on an isolated API 36 x86_64 / 4 KB emulator passed **1/1 disposable actual-activity scenario**, covering
all four explicit theme presets, Help navigation, persisted primary/backup order, actual backup versus preferred
primary, checking/all-down states, an exact response from that backup, and lazy scrolling to member **1003**.
Russian editor/Help rendering was separately checked through actual main-tab navigation with app-scoped locales.
The driver source was removed and the cleaned instrumentation APK rebuilt and installed.

`TunOwnerPolicyIntegrationTest` uses `run-as` with the separately installed test APK's regular application UID,
not the privileged shell UID. Its test-only manifest grants INTERNET so that UID can own real TCP/UDP sockets.
A minimal real VPN, with no default route or DNS, makes ownership visible under
[Android's owner-lookup visibility rules](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service/src/com/android/server/ConnectivityService.java#12979).
The test verifies the actual Binder UID before invoking production admission/identity callbacks; exclusion and
defense-disabled controls run with SOCKS authentication both off and on. An absent non-DNS tuple is rejected and
the existing DoT bootstrap exception is retained. This is **callback policy evidence**, not a native forged-packet
test; kernel app exclusion cannot stand in for admission denial. Package replacement/UID reuse remains unverified.

Hosted clean-checkout CI passed on `518e3a9`, including native regressions and both emulator page-size jobs.
Physical .31 ARM/ARM64 VPN/Live Routing and root verification remain unestablished.

### Connection analytics

The large main connection card opens an in-host full-screen destination; separate ping/Info actions and the compact
bottom control remain. `ConnectionAnalyticsViewModel` gates grouping/formatting on visibility, while the process-owned
`ConnectionRepository` continues capturing independently of Activities. Lists use flat, stable-key lazy rows, not
whole groups in one item. App keys include the UID/package set; known config keys use captured ID/fingerprint.
Captured names stay on each row; header labels cannot create duplicate keys or overwrite historical identities.

The service starts the IO reader only after actual native start, supplies final snapshots on stop, and marks external
root capture unavailable. Runtime generations reject late polls; core reload cannot overwrite the final old snapshot.
Package updates ingest outstanding history under the old mapping before adopting a new one. Reused/shared-changing
UIDs remain unknown for the process; admission uncertainty is metadata only and cannot change app-routing matching.
Profile names come from immutable native route bindings, never a current-profile lookup or a fallback display-name map.

`Settings.connectionHistoryOnDisk` defaults false. `connection-history.json` is an AtomicFile in `noBackupFilesDir`,
with at most 5,000 records / 8 MiB and a 24-hour closed-row TTL. Checkpoints are coalesced to five-second cadence plus
stop/preference boundaries. Restored active rows become interrupted/incomplete. Disabling deletes only this owned
file/backups and retains memory. Clear removes only finished records in native/memory/disk history. There is no Room
migration, payload/access-log journal, credential export or per-packet capture. Native buffer evictions and local drops
are explicit; totals are for retained filtered data, not an estimate of omitted connections.

Focused source-matched emulator scenario:

```bash
./tools/android-emulator.sh adb tablet shell am instrument -w -r \
  -e allowVpn true -e expectedPageSize 4096 \
  -e class io.github.derundevu.yaxc.VpnDeviceIntegrationTest#connectionAnalyticsTracksRealAppAndConfigAcrossLiveRoutingTcpUdpAndActualScreen \
  io.github.derundevu.yaxc.regression.test/io.github.derundevu.yaxc.testing.RegressionTestRunner
```

This exercises real shell-UID TCP/UDP, retained old members through live selection/default changes, exact final
payload counters and the actual Activity's two grouping/filter/back surfaces. Ordinary no-auth coverage asserts
unknown ownership rather than guessing an app. `TunOwnerPolicyIntegrationTest` checks that stale shared-membership
analytics does not change the real UID returned to routing. Repository/presentation regressions cover late polls,
UID reuse, delayed route identity, cap/TTL/clear/restore and opt-in/private persistence. Obtain current full-suite and
4 KB/16 KB evidence from `PROJECT_STATE.md`; physical devices, root and battery/performance profiling are separate gates.

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

### Runtime log viewer

`LogsActivity` uses a ViewModel-owned reader and a stable-key lazy list, not one ever-growing text node.
Normal mode reads only the app's UID from logcat, including VPN/helper tags previously excluded; it does not
request access to other apps' logs. Transparent-proxy mode follows only the existing core log file, without
root commands or runtime changes. Missing files wait for appearance; append, truncation and replacement are handled.
Initial history is the last 500 lines (also bounded to 256 KiB for file input). Closing the screen cancels and
reaps its owned logcat process; Activity recreation retains the reader, filters, pause and reading position.

History retains at most 5,000 physical rows and 4 MiB of UTF-8 raw text, including newlines; oldest rows are evicted.
An oversized physical row is visibly truncated around 16,384 UTF-16 characters without splitting a surrogate pair.
Parsing and case-insensitive literal search run off Main. Level/source filters are exact, and unrecognized rows
remain visible as unknown-level entries. Explicit native severity inside GoLog takes precedence over its Android
priority. Pause freezes the displayed snapshot while bounded collection continues; resume shows current history.
Scrolling into history disables follow, while “To latest” restores it. Clearing affects only local viewer history,
never global logcat buffers or runtime files. No access-log journal, native verbosity change or persistent log store.

Copy supports one row or the displayed filtered selection. Selections above 128 Ki UTF-16 characters are rejected
with an export hint rather than truncated or sent as an oversized Binder payload. Document export writes UTF-8
through Android's system picker and retains the selected snapshot while the picker is open. Logs are not redacted;
review them before sharing. Clipboard previews are marked sensitive on Android 13+.

`RuntimeLogReaderTest`, `LogHistoryTest` and `LogsViewModelTest` cover blocked-read cancellation/reaping, bounded
framing, UTF-8, file lifecycle, parser precedence, pause/clear/search and export snapshots. API 36 x86_64 smoke
exercised actual own-UID logs from four source classes, combined filters, pause/copy, system document export,
1,000-row scroll/recreation, a 6,200-row bounded stream, clipboard overflow rejection and local-only clearing.
The exported file contained exactly the captured filtered row; screen close left no owned logcat process.
Actual Android temporary-file following passed without enabling root mode. Disposable smoke code is not retained.

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
runs one core. A Main-thread scheduler in `MainViewModel` admits work before launching a coroutine: at most five
active measurements, at most one standalone single, and ten waiting distinct single requests. With no singles,
a batch can use all five slots. A single takes the next free slot without cancelling active batch probes;
other slots remain available to the batch. A full single queue produces localized feedback instead of another job.
Duplicate queued/in-flight requests share one measurement. A queued batch member requested individually is
promoted to single priority; its one result also advances batch progress. Remeasuring an already completed member
does not advance progress twice. Repeating the same active batch is a no-op; a different batch replaces only
waiting batch work, preserving admitted singles and active native ownership.

The ViewModel retains work across Activity recreation. Clearing it cancels active calls; cancellation retains
each slot/config/binding until native teardown, and cancelled queued acquisition returns any undelivered slot.
Deleted active profiles keep their reservation until completion but cannot reappear through late results.
The helper's shared worker pool also bounds direct callers. Pings never switch the selected profile or reload
the VPN; request IDs reject duplicate/stale completions. No native source change is needed for this scheduler;
rebuild the AAR only after native source changes. See [native contracts](NATIVE.md#ping-worker).

`MainPingTrackerTest` covers floods, promotion, shared measurements, independent progress, replacement,
deletion and stale completions. An API 36 x86_64/4096-byte-page runtime smoke exercised the actual Activity,
Room and native workers: 1,500 ping actions produced 17 unique measurements, ten waiting singles and a peak of
five probes/one standalone single. A malformed HTTP response settled as an error without stalling the queue.
Activity recreation retained in-flight probes; closing the screen with five held requests closed their peers,
removed temporary configs and allowed all five slots to be reused. Russian queue feedback, concurrent batch
progress and settled delay screenshots were inspected. Disposable smoke sources and fixtures were removed.

Native regressions, using this checkout's local forks:

```bash
(cd XrayCore && go test -mod=readonly -count=1 -timeout=240s ./...)
(cd XrayCore && go test -mod=readonly -race -count=1 -timeout=240s . ./lib)
(cd XrayCore && go test -mod=readonly -count=1 -timeout=90s github.com/xtls/libxray/nodep github.com/xtls/libxray/share)
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
The published **1.5.3 / Xray 26.10.31** baseline source pins and release are described in
[Native dependencies](NATIVE.md#sources-and-versions). Hosted CI on `518e3a9` does not cover later local changes.

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
