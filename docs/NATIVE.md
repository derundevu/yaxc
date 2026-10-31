# Native dependencies and forks

Read this only for native code, dependency or build changes. Commands run from the repository root;
see [Development](DEVELOPMENT.md#build) for toolchain requirements and ABI-specific builds.

## Sources and versions

All four direct submodules already use `derundevu` forks. [`.gitmodules`](../.gitmodules) defines URLs;
parent-repository gitlinks pin exact commits, not the latest fork branch HEAD.

| Submodule | GitHub fork | Consumer / output |
| --- | --- | --- |
| `XrayCore/Xray-core` | `derundevu/Xray-core` | Go core in `app/libs/XrayCore.aar` |
| `XrayCore/libXray` | `derundevu/libXray` | Wrapper in the same AAR |
| `XrayHelper` | `derundevu/XrayHelper` | `app/src/main/assets/xrayhelpers/<Android ABI>`, root runtime |
| `app/src/main/jni/hev-socks5-tunnel` | `derundevu/hev-socks5-tunnel` | tun2socks/JNI, built by NDK through Gradle |

The tunnel's nested submodules have their own URLs and pinned commits; they were not all moved to these forks.
[`XrayCore/go.mod`](../XrayCore/go.mod) replaces `github.com/xtls/xray-core` and `github.com/xtls/libxray`
with local directories. Do not rename module paths/imports just because the Git remote is a fork.

The current local candidate is upstream **v26.10.29**, `0afec66085a85191e9dd2a4a6646c7c9a94688d1`, with
failed-construction cleanup and synchronized per-connection splice state. Neither fork fix is present upstream.
The app keeps process-isolated validation, the retained libXray facade, schema adaptations and ping fixes.
The effective core module version is `v1.260327.1-0.20261029083850-0afec66085a8`; its prefix is not the runtime version.
Go **1.27.3** and the app's gomobile pin are unchanged. The owner published both
`yaxc/migration-26.10.29` branches: core `af4438108139e7e4522a2cdb8869593b29ab6649` and retained wrapper
`da595ebc37d1f30aba81c178218757fc954d518b`. The core commit records both the previous fork and upstream .29 parents.
Parent commit `9d86576` records these gitlinks. Release packaging remains a local change; no new hosted CI,
tag or release was triggered by the assistant.
XrayHelper `fbf155ede163062bf85de39a49dca82fdfbc9b1c` and tunnel
`9cbf5644b493bbc5659ef0b4db470c969259e085` are unchanged.

The tunnel candidate is **2.19.3**, `c5584feca8f6df70ee53eb2879e034b849a3d15a`, with the custom owner-check/teardown
port. Its exact upstream nested pins are core `dadbb54bf7509f6dea3c1b30e45198efa400679c`, task system
`d0365c370aadd39129924b563b80751cb63332d7`, lwIP `495671e59d736ef59f6cfa7dc9d35b7359921062` and YAML
`94441ec8777eb319936735a58b376963d09c73d4`. The task-system update removes the TLS model causing JNI loading errors.

XrayHelper is based on `b42f3430fb423f8a8ae74e312cc8d65eeb374ea1`, with Go raised to 1.27.3. The upstream delta
only changes Go metadata, not proxy rules. Its build script now fails on errors. Rootless CLI/config checks pass on
host and Android; actual root/iptables behavior is unverified because the connected device has no root.

## Change workflow

Check versions and working-tree edits before switching anything:

```bash
git submodule status --recursive
git submodule foreach --recursive 'git status --short'
```

The first command reports `-` for an uninitialized checkout, `+` for a commit differing from the gitlink,
and `U` for a conflict. The second detects edits within each checkout; a clean tree alone does not prove the pinned commit is checked out.
Use `git submodule update --init --recursive` only when safe for local work. Never use `--remote`, `reset --hard` or `clean` as routine build preparation.

1. Make dependency changes in the relevant fork, not only as an unrecorded submodule working-tree patch.
2. A reproducible update needs a published fork commit and an updated parent gitlink. The agent prepares changes;
   the owner handles commits/publishing. Never pin a commit unavailable to other checkouts or CI.
3. Keep compatibility fixes in the pinned forks, not in a parallel patch-file build path.
4. Rebuild the affected AAR/helper for the target ABI after Go/source/version changes, or JNI/APK after tunnel changes.
   Existing artifacts do not prove that new native sources build.
5. Verify a clean checkout build and the changed runtime scenario. Obtain approval before interrupting the user's VPN or root runtime.

## Fork-only builds

Native builds consume pinned fork commits directly; local scripts and CI do not apply compatibility patches.
The tunnel's owner-check/teardown port is included in fork commit `9cbf5644b493bbc5659ef0b4db470c969259e085`.
Gradle builds the tunnel from its submodule; `buildXrayCore.sh` builds the core and wrapper from theirs.
The redundant tunnel patch, application script and push/PR/release CI steps have been removed.
Tun hardening remains experimental, not a system Kill Switch guarantee.

Both native builders accept `all` in addition to `arm`, `arm64`, `386` and `amd64`. The all-target core build
emits one AAR containing all four JNI ABIs. Helper builds replace the generated asset set with only the requested
architectures, removing the old flat asset. Android selects the first device-supported ABI present under
`assets/xrayhelpers/` and copies it to the unchanged on-device `xrayhelper` path. The plural asset namespace avoids
an incremental asset-merger file/directory collision with the former flat asset. Root mode still requires its
separately supplied Xray executable; packaging the helper is not proof of root/iptables behavior.

## Embedded facade lifecycle

The initial 29 old-core edits were withdrawn before selecting upstream v26.10.21. The constructor cleanup invariant
was still missing upstream: real log-FD probes failed without the two `Close` calls and passed with them.
The remaining race is now fixed narrowly: inbound/outbound splice fields are `atomic.Int32`, all consumers use
atomic operations, and metadata copies take an independent snapshot. Readiness is published only after a successful
final protocol write; a conditional transition cannot overwrite a forbidden state. Splice optimization is not disabled.

`XrayCore/lib` retains its lifecycle lock, active-owner guard and failed-start cleanup. The old `Test`
entrypoint is replaced by `ValidateInWorker`: it loads the full configuration, calls upstream `core.New`,
closes the result without `Start`, and restores the asset-location environment variable. It rejects an
app-owned active core before reading the candidate or changing globals. External cores remain undetectable;
callers must still isolate construction in a dedicated process.

Both Android callers use `XrayValidationHelper` and the non-exported `ValidationService` in `:validation`.
Each request has private, unique config and diagnostic cache files; Binder carries paths and status, not
config/diagnostic text. A mutex serializes requests, and the process-lifetime native dispatcher prevents
constructor overlap across service recreation. Cancellation keeps the files, binding and slot until native
completion or worker death: `core.New` itself is not interruptible. Queued cancellation starts no native work.
Application initialization skips this process, as it does the ping workers.

Service commands and runtime ownership are serialized separately from validation-worker cleanup. STOP cancels
pending configuration work and tears down the active runtime without waiting for an uninterruptible constructor;
the validation helper still retains its own binding/files/slot until acknowledgement. Runtime config publication
shares the ownership lock, and teardown uses the active mode rather than edited preferences. JNI loads only at
the production native boundary; static status/commands are host-safe and native class/method symbols are unchanged.

Conditional `NEW_CONFIG` compares the generated JSON and captured platform options with the last successfully
applied runtime. Equal candidates do not validate, rewrite `config.json`, stop Xray or replace TUN. Metadata labels
use `STATUS`, not a reconnect event. Changed candidates are validated in isolation, then re-read from committed
state under the Room transaction before serialized application; removed selection stops the runtime. The focused
API 35 x86_64 4 KB smoke keeps one other-UID TCP stream alive through real unchanged/metadata/unselected-source
refreshes, then applies a meaningful destination change. This new scenario has not been exercised on physical/root
or 16 KB devices.

The complete required native race suite now passes, including isolated validation during chained exact-payload
traffic and active logger checks. A real-pipe regression covers blocked/failed writes and splice-state transitions.
Focused core protocol/mux race tests also pass. A supplementary whole-core `go vet -copylocks ./...` still reports
unrelated upstream protobuf-message copies in XHTTP/MASQUE/xdrive; no suppression was added.

Approved arm64/API 34 VPN smoke exercised TCP/UDP/DNS over IPv4/IPv6, the system resolver, per-app allow/reject,
reload, STOP during blocked validation, immediate native start/stop and teardown. This does not establish root parity
or exhaustive service-mode/revoke/concurrency behavior.

The permanent [Android suite](DEVELOPMENT.md#isolated-android-instrumentation) also passes all 18 cases on
dedicated API 35 x86_64 emulators with 4 KB and 16 KB pages. It uses real native workers, exact proxy payloads,
another UID's TUN traffic, rejected reload, restart and STOP while `core.New` is blocked between two FIFO log
sinks. The first FIFO rendezvous proves native construction entered before STOP; the second keeps it blocked
until teardown is observed. Geodata FIFOs are not used: the current asset loader correctly rejects non-regular files.

### Retained wrapper compatibility

The app-owned file-path/Base64 API and committed ping cancellation/deadline fixes are retained, not replaced
with Invoke API v3. Required target-schema changes move Hysteria bandwidth/hopping into FinalMask/QUIC,
preserve its masks through `fm`, map the two renamed XHTTP session keys and update geodata protobuf types.
Shadowsocks UoT and `none`/`plain` ciphers no longer have a native mapping; imports fail explicitly instead
of returning a successful subset. Other partial-import behavior remains unproven.

The removed MPH-cache API, request fields and C/desktop consumers were removed together; no no-op shim remains.
The standalone builder's source tag and retained module metadata now select the same target, but the app
build entry point remains `buildXrayCore.sh`.

The 26.10.29 wrapper port adapts the upstream `dbe95129..93af542c` target-metadata changes and selects
`go-proxyproto` 0.16.0, required by the target core. It does not merge the older Invoke v3 redesign or unrelated
modern-wrapper dependency upgrades. The standalone builder's existing tool policy is separate from the app's locked build.

The Android script installs gomobile/gobind from the pinned root module graph and fails on tool/build errors.
It calls `gomobile bind` directly: `gomobile init` would reinstall `gobind@latest` and is only needed for optional
OpenAL setup, which this app does not use. Its `-checklinkname=0` matches
[upstream Android builds](https://github.com/XTLS/Xray-core/blob/v26.10.29/.github/workflows/release.yml)
for anet. Explicit linker page flags give 16 KB LOAD alignment/congruence and page-aligned RELRO ends.
All four core AARs passed those checks. The arm64 APK passes `zipalign -c -P 16 4`; bundled
`libandroidx.graphics.path.so` still has strict RELRO-end remainders despite 16 KB LOAD alignment.
Actual API 35 x86_64 4 KB/16 KB guests now load that unchanged prebuilt and execute its path-geometry API,
as well as real core/JNI/VPN traffic. Physical arm64 16 KB loading remains a separate, unverified gate.

### 26.10.29 candidate verification

The local source build passed the required facade race/ownership/cancellation suite, nodep checks and focused
splice/protocol regressions. DNS, geodata, filesystem/platform and configuration checks also passed under the race detector.
A throwaway host runner transferred exact 64 KiB requests/responses through VLESS raw, WebSocket and XHTTP to both
26.10.21 and 26.10.29 peers, before/after isolated validation and restart: six scenarios, 18 transfers.
It does not establish arbitrary REALITY/VLESS Encryption profiles or root parity.

All four core AARs rebuilt on Go 1.27.3; their ELF LOAD alignment/congruence and RELRO ends pass 16 KB checks.
Matching debug APKs for arm/arm64/x86 and the isolated x86_64 regression APK pass ABI, version and ZIP-alignment checks.
The earlier arm64 debug build, release Kotlin compilation, 313 JVM tests, debug lint and release lint passed;
their artifact hashes/provenance remain under ignored `app/build/xray-26.10.29/artifacts.json`.
The universal-packaging change rebuilt all four native targets and restored an all-ABI AAR/helper baseline.
All five release outputs use code **1175** and pass ABI/ELF, signature and 16 KB ZIP-alignment checks, using
the local debug signing identity only for smoke verification. **317 JVM tests** and debug lint passed.
A universal regression APK passed all ten native integration cases, graphics/native loading and helper selection/
extraction on an API 36 x86_64 / 4 KB emulator. Its extracted helper ran `--version` without root. ABI → universal
→ ABI replacement at equal code preserved a synthetic application-data marker. These checks do not replace
the full VPN/page-size CI gates or prove the production release signature. Packaging evidence is under ignored
`app/build/universal-release-smoke/`; no hosted CI, production-signed release or publication was performed.
The published .29 fork commits were also cloned independently over HTTPS and built with the candidate's root module
graph. The actual facade reported 26.10.29 and passed validation, an exact 24 KiB SOCKS request/response, Stop and
listener release. This proves those native source pins are available and buildable, not a full parent checkout or device CI.

Local API 35 device checks used emulator 37.2.13, not the CI-pinned 36.6.11. The first unchanged 4 KB suite passed
16/19 cases (including the established TCP-flow/reload case); three HTTP-through-TUN cases failed without a reply.
A 26.10.21 control also failed one VPN case, so a new-core regression is not established. Network-wait experiments
were inconclusive and withdrawn; test sources/assertions are unchanged. On the 16 KB guest, the ten native cases and
graphics/native-loading case passed, but established TUN traffic timed out and the full run did not complete.
These are release-gate failures/limits, not an all-green VPN claim. Require both hosted device jobs before tagging.

An additional server-side WebSocket/TLS race run failed in `transport/internet/tls/config.go`: the certificate
reload/OCSP goroutine writes the certificate slice while `GetCertificate` reads it. This file is unchanged between
the two upstream tags; the finding was not suppressed or patched as part of the core-version update.
Do not describe the entire upstream race suite as green. This server certificate path is distinct from normal client
TLS outbounds; custom TLS-inbound configurations retain the upstream risk.


## Tunnel runtime contract

The YAXC instance binding now uses Boolean `TProxyStartService`/`TProxyStopService` and `TProxyIsRunning`, with no
old void aliases. Start success means the worker thread was created, not that asynchronous tunnel initialization
finished. Stop joins the tunnel before releasing its owner callback worker/global reference; join failure retains
live ownership. Android checks start/stop results and uses native running state. Stats order remains
`[txPackets, txBytes, rxPackets, rxBytes]`; the Android TUN descriptor remains borrowed, not closed by native code.
The upstream standalone AAR packaging is not an app consumer and was not verified.

The owner kept the inherited UDP datagram limit instead of adding a capacity feature. Current receive slots are
1756 bytes including SOCKS headers; baseline 2.14.4 had only 1500. A real 4096-byte echo at MTU 1280 timed out and
remains a failed unsupported-capability probe, not a passed test. The accepted VPN smoke uses exact 512-byte UDP
datagrams. Oversized `MSG_TRUNC` replies remain explicitly discarded; reassembly/capacity guarantees were not expanded.


## Ping worker

Xray initialization replaces process-global dialer/logger state. Android pings therefore use at most five
non-exported worker services in `:ping_1` through `:ping_5`, with one native core at a time in each process.
No worker shares the VPN core's process. Services bind on demand; a cancelled or completed request returns its
pool slot only after native teardown is acknowledged. Queued cancellation must not lose a slot.
Requests use `PreparePing`, `CancelPing` and `PingPrepared`. GET, HEAD and TCP retain their request context
and configured deadline, including SOCKS negotiation. Worker-process names must also match the `Yaxc` initialization guard.

The retained `nodep.CallResponse[int64]` uses numeric `omitempty`: a successful zero-millisecond result has
`success=true` with no `data` member. Android decodes that representation as zero, not a missing-result error;
explicit negative/malformed values remain failures. A native 16 KB-emulator TCP probe exposed this boundary,
and a deterministic JVM regression now fixes the exact omitted-zero representation.

The retained libXray fork supplies the ping reliability fixes. Selected-core HTTP/SOCKS deadline/cancellation
tests pass. A real Android probe also passed GET/HEAD/TCP over native VLESS/XHTTP, admitted five worker PIDs,
cancelled ten active/queued requests, recovered every slot and preserved a separate sentinel core's traffic.
The source-built host target observed five simultaneous held requests and all five connections closing on cancellation.
