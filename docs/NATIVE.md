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

The published **26.10.29 migration baseline** uses upstream `0afec66085a85191e9dd2a4a6646c7c9a94688d1`, with
failed-construction cleanup and synchronized per-connection splice state. Neither fork fix is present upstream.
That baseline keeps process-isolated validation, the retained libXray facade, schema adaptations and ping fixes.
The published baseline selected `v1.260327.1-0.20261029083850-0afec66085a8`; its prefix is not the runtime version.
Go **1.27.3** and the app's gomobile pin are unchanged. The owner published both
`yaxc/migration-26.10.29` branches: core `af4438108139e7e4522a2cdb8869593b29ab6649` and retained wrapper
`da595ebc37d1f30aba81c178218757fc954d518b`. The core commit records both the previous fork and upstream .29 parents.
Parent commit `9d86576` records those historical gitlinks; 1.5.3 later published the .31 migration.
At that published baseline, XrayHelper was `fbf155ede163062bf85de39a49dca82fdfbc9b1c` and the tunnel was
`9cbf5644b493bbc5659ef0b4db470c969259e085`; these pins do not include the later Live Routing source edits below.

The published tunnel baseline uses upstream **2.19.3**, `c5584feca8f6df70ee53eb2879e034b849a3d15a`, with the
custom owner-check/teardown port. Its then-upstream nested pins were core `dadbb54bf7509f6dea3c1b30e45198efa400679c`, task system
`d0365c370aadd39129924b563b80751cb63332d7`, lwIP `495671e59d736ef59f6cfa7dc9d35b7359921062` and YAML
`94441ec8777eb319936735a58b376963d09c73d4`. The task-system update removes the TLS model causing JNI loading errors.

XrayHelper is based on `b42f3430fb423f8a8ae74e312cc8d65eeb374ea1`, with Go raised to 1.27.3. The upstream delta
only changes Go metadata, not proxy rules. Its build script now fails on errors. Rootless CLI/config checks pass on
host and Android; actual root/iptables behavior is unverified because the connected device has no root.

The **1.5.3 / Xray 26.10.31** sources and Live Routing changes are published and pinned in parent HEAD `518e3a9`:
Xray-core `e3802a5c62bedc68007ff37989680de12978fd43`, retained libXray
`ae5f78b63aee108c4fc21d08be8221fb2f5f8e41`, tunnel `27be1788bf263bdb65fdfe148de3ef788611b255`
and XrayHelper `fbf155ede163062bf85de39a49dca82fdfbc9b1c`. The tunnel pins the published owner forks of
SOCKS core `8d27d5a41bd46909f1ea8f83a5c2adff8e7b7537` and lwIP `a0fcbb8d509406a2ddb327ee5efad3941f8f2efe`.
[Hosted CI](https://github.com/derundevu/yaxc/actions/runs/41885589420) passed for `518e3a9`, including the
4 KB/16 KB emulator matrix; it does not cover later uncommitted work. [1.5.3](https://github.com/derundevu/yaxc/releases/tag/v1.5.3)
was published from tag `8b1a521`.

Upstream [v26.10.31](https://github.com/XTLS/Xray-core/releases/tag/v26.10.31) is an urgent security update and
withdraws .29. It disables listener MPTCP by default, preserving explicit opt-in; kernel security updates remain
necessary. The core merge includes upstream `a4a3dff5b20c2faae567b742c172c7b95e75c083` and the retained fork fixes.
Root/wrapper modules select official version `v1.260327.1-0.20261031132019-a4a3dff5b20c`; Go 1.27.3 and
local replacements remain. The wrapper's standalone tag is .31; XHTTP export preserves `h2Params`, legacy
session-field normalization, unknown `extra` options and `extra` precedence.

Retained custom fixes include constructor cleanup, atomic splice state, managed routing/candidates, exact-tag
probes/cancellation and SOCKS account snapshots. The expanded gate also exposed the upstream certificate/OCSP
race: encipherment certificates now use immutable atomic snapshots and private watcher inputs. Invalid reloads
retain the last valid snapshot. A real HTTPS file-reload smoke verified new peer certificates and exact payloads
without mutating the proto input. QUIC close assertions are synchronized; ECH tests use real loopback DNS/TLS,
including concurrent cached handshakes and no HTTP delivery after failed ECH setup, instead of public endpoints.

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

Publish dependency changes bottom-up: modified nested lwIP/SOCKS-core forks first, then their URLs/gitlinks and
sources in the enclosing tunnel. The customized Xray-core and, when aligning its core requirement, libXray form
the other chain. Publish those before the parent pins them. Keep parent Go facade, matching Android/JNI callers,
tests and required gitlinks in a coherent buildable change; `XrayCore/lib` belongs to the parent, not libXray.
Preserve any local edits before preparing a separate migration branch; do not rebase/reset them.

The 1.5.3 source chain is already published and pinned. For future dependency changes, publish new leaf commits
before their consumers and run clean recursive-checkout CI on the exact parent revision. Release jobs do not wait
for the separate CI workflow. Local unsigned or debug builds are not production-signing proof.




## Fork-only builds

Native builds consume submodule sources directly; local scripts and CI do not apply compatibility patches.
The published tunnel `27be178` includes the owner-check/teardown port and Live Routing source chain.
Gradle builds the tunnel from its submodule; `buildXrayCore.sh` builds the core and wrapper from theirs.
The redundant tunnel patch, application script and push/PR/release CI steps have been removed.
Tun hardening remains experimental, not a system Kill Switch guarantee.

Both native builders accept `all` in addition to `arm`, `arm64`, `386` and `amd64`. The all-target core build
emits one AAR containing all four JNI ABIs. Helper builds replace the generated asset set with only the requested
architectures, removing the old flat asset. Android selects the first device-supported ABI present under
`assets/xrayhelpers/` and copies it to the unchanged on-device `xrayhelper` path. The plural asset namespace avoids
an incremental asset-merger file/directory collision with the former flat asset. Root mode still requires its
separately supplied Xray executable; packaging the helper is not proof of root/iptables behavior.

## Live Routing candidate (2026-11-01)

The Android compiler composes all group members' namespaced outbound graphs across subscriptions. Independent
Live Routing connections use global DNS, inbounds and Core Routing; ordinary profile connections retain the selected
profile's shared base configuration. Supported chain references are rewritten; incomplete, cyclic or unsupported
graphs are rejected. Select, Random and round robin retain their original semantics unless the optional
`checkAvailability` flag is enabled (default false). LeastPing requires that flag and always checks availability.
Managed rules precede Core Routing: first enabled match wins, app/domain fields are ANDed and each list is ORed.
Bare/domain-suffix, `full:`, `regexp:`, `geosite:` and `ext:<file>:<tag>` matchers are accepted; hostname forms use IDN ASCII.

`ApplyRouting` takes a complete generated configuration under the facade lifecycle lock. It stages new outbound
handlers outside old balancer selection, resolves graph dependencies and replaces the router snapshot at commit.
Unchanged handlers are reused; retired real handlers stay alive until explicit Stop because Xray has no general
per-handler flow-drain primitive. New flows use the accepted snapshot while established streams retain their paths.
The service does not restart the core/TUN or switch the selected profile for an accepted live update. Failed
validation/staging keeps the previous runtime; live rejection never falls back to an automatic restart.
DNS, listeners, routing-resolution settings and platform options are not live-switchable through this path.

The live-update retention budget is **512 real outbound-handler versions per running session, including the initial
handlers**. All group members remain loaded, so manual member/strategy/rule-only changes consume no new versions.
Endpoint additions or graph changes can create versions (including dependent handlers); repeated changes can
exhaust the budget. The native diagnostic then requests an explicit reconnect to release retired connections.
Zero-resource aliases and routing-only updates do not consume that budget. Missing members never select a replacement.

Health-enabled groups compile to ordinary random/round-robin balancers initially selecting only a dedicated
blackhole, plus strict `_yaxcLiveHealth` metadata. Select supplies one highest-priority eligible candidate; LeastPing
supplies the eligible member with the lowest last successful nonnegative delay, using configured order for ties.
Random/round robin receive all eligible candidates. The fork router's `ReloadRulesWithCandidates` atomically binds
managed candidate sources and their explicit reject handlers. Unknown, empty, expired or failed sources select that
blackhole directly; a balancer-selection error/default-outbound fallback is never used as a blocking mechanism.
API overrides cannot bypass a managed candidate source.

The managed controller reuses Observatory's exact-tag HTTP GET probe. Stock Observatory/Burst cannot provide its
contract: their selectors are immutable, observations are logical-tag based, and existing strategies can admit
unknown members or retain ordinary candidates on observer errors. Stock observed-balancer restrictions below remain.
The controller has four workers, a configurable 5–3600-second per-member start cadence (default 10), a five-second
request timeout, two-failure eviction and three-success recovery; an untested graph qualifies after its first success.
Immutable per-plan options come from optional strict integer `intervalSeconds` metadata; omission retains defaults.
Accepted interval changes reschedule from the last probe start without discarding matching in-flight probes.
The staleness bound is `3 * interval + timeout` (35 seconds by default); expiry requires three successes again.
Queueing can delay large groups. A transient failure retains the last successful latency while health hysteresis
controls eligibility. Only HTTP 2xx qualifies; redirects/direct fallback are disabled. Ping Address supplies the HTTP(S) URL.

Observation identity is the immutable native root-handler version, including changed dialer dependencies, and probe
URL. Source/profile ID or a supplied UI fingerprint cannot transfer health to another graph. Hot priority/rule changes
reuse matching observations. Staged sources are populated and committed with the router under the observation lock,
so a healthy rules-only update exposes neither an empty source nor stale observations. Removed/replaced probes are
canceled; Stop joins all four workers before closing core resources. Managed probes opt into cancellation propagation
through `dialerProxy` and cancellation-bound private outbound connections, including synchronous proxy handshakes.
Ordinary traffic keeps its detached response-draining lifetime; shared mux physical workers do not inherit this flag.
Health transitions only publish immutable candidate sets, without reloading handlers/router, restarting Xray/TUN,
migrating an existing stream or retrying a partially transmitted user request.

Existing observatory-backed balancers remain distinct from the new groups. `leastPing`, `leastLoad` and Random/round
robin with a fallback retain their **logical selectors** while observed handlers are unchanged, so observations still
refer to the real logical tags. Changing those observed handlers rejects the update and requires an explicit reconnect,
instead of applying stale health results to new versions. API/metrics service-registered targets outside JSON
`outbounds` remain valid; removed managed targets and private retained versions cannot masquerade as service targets.

App identity requires Android 10+ VPN owner lookup and authenticated SOCKS. The tunnel authenticates as `yaxc.uid.<UID>`;
the core supports live per-UID accounts without replacing the SOCKS listener. Shared UIDs are indistinguishable.
Unknown owners keep the default account and never app-match; excluded apps bypass the VPN entirely. The compiler
explicitly identifies the default account with `_yaxcDefaultSocksUser`: native user rules matching that account
(including regex rules) also cover its managed UID identities on initial start and live apply. Other users' rules are
not inherited merely because accounts share a password. Domain-only rules work in SOCKS-only mode; root transparent
mode explicitly rejects this feature rather than silently ignoring its plan.

The existing `shouldAllowTunConnection` admission policy is unchanged from the release base. Native sessions invoke
it with the original TCP/UDP tuple before connecting to SOCKS; assigning a managed app identity happens only after
admission succeeds. With defense enabled, non-DNS unknown owners and lookup errors reject; own/system UIDs and
unknown DNS/DoT bootstrap flows are existing exceptions. This remains experimental hardening, not a kill switch.
The UID-only admission cache is also pre-existing. [INFERENCE] Reuse of an allowed UID after package replacement
can leave a stale admission decision until the cache is cleared; package-replacement/UID-reuse was not exercised.

Managed UID accounts share the configured SOCKS password. A direct SOCKS client with that password can claim a
managed app identity without passing through the TUN admission check. Treat app matching as routing, not an
authorization boundary against clients holding proxy credentials; do not give those credentials to untrusted clients.

`LiveRoutingStatus` returns runtime group/member IDs, source fingerprints, hysteresis-adjusted states, eligible IDs
and the Select/LeastPing candidate for new streams. Random/round robin deliberately have no single active member. The status
contains no config, endpoint or raw probe diagnostic. Android reads it on IO about once per second and compares the
strategy, preferred ID, ordered members and fingerprints before labeling a database profile as the runtime choice.
An unsaved draft, rejected refresh or changed source cannot masquerade as the applied runtime.

Saving persists the local plan and requests active-session application, not proof of runtime acceptance.
See [targeted host/device commands](DEVELOPMENT.md#live-routing) and [user setup](../README.md#live-routing).
The 2026-11-06 parent-facade changes add configurable intervals and managed LeastPing without changing fork pins.
The source-built all-ABI AAR passed facade/library Go race tests. An API 36 x86_64 / 4 KB emulator exercised changed
winner/5→8-second intervals, real new-flow recipients, retained TCP and unchanged TUN, plus existing failover/recovery
and endpoint-replacement cases. Physical devices, root and a new 16 KB runtime check were not exercised for this change.
Earlier, before availability support, local verification used an all-ABI Go AAR and an isolated API 36 x86_64 / 4 KB emulator: **367 JVM tests**,
lint without errors, and **17 native/VPN/page-size cases** passed. The VPN scenarios exercised cross-source
app/domain rules, manual and round-robin changes while an established TCP stream survived, and exact standard-UDP
replies before/after group selection. Real host SOCKS/default-account/API-counter and observatory/fallback smokes passed.
The actual UI was opened from the main Routing tab; mixed-source selection, picker cancellation/rotation, save and
reopen were exercised with synthetic profiles.
An additional disposable driver selected another member and tapped **Save** in the real activity while the VPN
was active: new requests reached the other peer and the original TCP stream continued (**1/1**). Its source was removed.

The final .31 candidate rebuilt **all four ABI** core AAR libraries, helpers and JNI, plus four split APKs and
a universal APK for each of debug, regression and unsigned release. All **15 APKs** passed ABI/helper/version
and 16 KB ZIP checks; core/tunnel LOAD/RELRO checks passed. Production signatures were not available locally.
Go race gates passed for the facade/library, retained wrapper, Observatory/router/outbound, core, splice/protocol,
TLS/XHTTP, configuration, sniffing and geodata packages. New certificate/OCSP and ECH regressions are isolated.
Automatic synthetic probes no longer block on an undrained observation queue; manual probes remain controlled.
The library's unchanged four-worker traffic/200-hot-apply scenario passed after repairing that fixture.

Final isolated **API 35 x86_64** guests measured **4096** and **16384** byte pages and each passed **28/28**
instrumentation cases, including production-cadence availability transitions, exact recipients, retained TCP/TUN,
ordinary VPN lifecycle, owner callbacks, UDP and native/graphics loading. The actual UI displayed **26.10.31**
and exercised Routing → Live Routing → Help → Back. Local emulator **37.2.13** differs from CI's pinned **36.6.11**.
An earlier .29 real-SOCKS smoke measured three-success recovery at **30.113s**; it is baseline evidence only.
Hosted CI later passed on `518e3a9`; physical .31 ARM/ARM64 VPN and root behavior remain unverified.

Keep standard SOCKS UDP enabled (`socksUdp=true`, the default). The legacy disabled setting selects HEV's
UDP-over-TCP `FWD_UDP` command 5, but the bundled Xray SOCKS inbound accepts TCP CONNECT command 1 and standard
UDP ASSOCIATE command 3, not command 5. It is not a supported second UDP transport. The standard-UDP routing scenario
above does not establish support for that legacy mode.

## Embedded facade lifecycle

During the historical **26.10.21 migration**, the initial 29 old-core edits were withdrawn before selecting that tag.
The constructor cleanup invariant was still missing upstream: real log-FD probes failed without the two `Close` calls and passed with them.
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

Conditional `NEW_CONFIG` compares the generated JSON, package-identity snapshot and captured platform options with
the last successfully applied runtime. Equal candidates do not validate, rewrite `config.json`, stop Xray or replace
TUN. Metadata labels use `STATUS`, not a reconnect event. Changed candidates are validated in isolation, then re-read
from committed state before serialized application. Routing/outbound-only candidates use `ApplyRouting` when shared
settings are unchanged; other core changes reload Xray and changed platform options require a validated restart.
`NEW_CONFIG` with removed selection stops the runtime; explicit live apply rejects missing selection without
stopping the applied session. Rejected live updates never force a reconnect.

Historical **pre–Live Routing 26.10.29** verification: a focused API 35 x86_64 / 4 KB smoke kept an other-UID TCP
stream alive through unchanged/metadata/unselected-source refreshes, then applied a meaningful destination change.
The then-required native race suite passed, including isolated validation during chained exact-payload traffic
and active logger checks. A real-pipe regression covered blocked/failed writes and splice-state transitions.
Focused core protocol/mux race tests also passed. A supplementary whole-core `go vet -copylocks ./...` reported
unrelated upstream protobuf-message copies in XHTTP/MASQUE/xdrive; no suppression was added.

Historical **pre–Live Routing** arm64/API 34 VPN smoke exercised TCP/UDP/DNS over IPv4/IPv6, the system resolver,
per-app allow/reject, reload, STOP during blocked validation, immediate native start/stop and teardown. This does not establish root parity
or exhaustive service-mode/revoke/concurrency behavior.

The historical **pre–Live Routing** [Android suite](DEVELOPMENT.md#isolated-android-instrumentation) passed all 18
cases on dedicated API 35 x86_64 emulators with 4 KB and 16 KB pages. It used real native workers, exact proxy payloads,
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

Historical **26.10.29 migration/packaging** evidence, before Live Routing; do not treat these results as verification
of the later live-update, UID-identity or tunnel source edits.

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
`app/build/universal-release-smoke/`; those historical local checks did not themselves publish a release.
The published .29 fork commits were also cloned independently over HTTPS and built with the candidate's root module
graph. The actual facade reported 26.10.29 and passed validation, an exact 24 KiB SOCKS request/response, Stop and
listener release. This proves those native source pins are available and buildable, not a full parent checkout or device CI.

Local API 35 device checks used emulator 37.2.13, not the CI-pinned 36.6.11. The first unchanged 4 KB suite passed
16/19 cases (including the established TCP-flow/reload case); three HTTP-through-TUN cases failed without a reply.
A 26.10.21 control also failed one VPN case, so a new-core regression is not established. Network-wait experiments
were inconclusive and withdrawn; test sources/assertions are unchanged. On the 16 KB guest, the ten native cases and
graphics/native-loading case passed, but established TUN traffic timed out and the full run did not complete.
These are release-gate failures/limits, not an all-green VPN claim. Require both hosted device jobs before tagging.

The historical .29 expanded server-side WebSocket/TLS race run failed in `transport/internet/tls/config.go`:
certificate reload/OCSP wrote data while `GetCertificate` read it. Upstream .31 retained that path. The current
local .31 candidate fixes the observed encipherment-certificate publication race and verifies immutable retained
snapshots; see the candidate section above. This is not an exhaustive upstream race audit or proof of every
authority-issued CA reload/custom TLS-inbound combination.


## Tunnel runtime contract

The YAXC instance binding now uses Boolean `TProxyStartService`/`TProxyStopService` and `TProxyIsRunning`, with no
old void aliases. Start success means the worker thread was created, not that asynchronous tunnel initialization
finished. Stop joins the tunnel before releasing its owner callback worker/global reference; join failure retains
live ownership. Android checks start/stop results and uses native running state. Stats order remains
`[txPackets, txBytes, rxPackets, rxBytes]`; the Android TUN descriptor remains borrowed, not closed by native code.
The upstream standalone AAR packaging is not an app consumer and was not verified.

Historical **pre–Live Routing tunnel-capacity probe**: the owner kept the inherited UDP datagram limit instead of
adding a capacity feature. Receive slots were 1756 bytes including SOCKS headers; baseline 2.14.4 had only 1500.
A real 4096-byte echo at MTU 1280 timed out and remained a failed unsupported-capability probe, not a passed test.
The then-accepted VPN smoke used exact 512-byte UDP datagrams. Oversized `MSG_TRUNC` replies remained explicitly
discarded; reassembly/capacity guarantees were not expanded. This is not evidence that Live Routing UDP passes.


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

## Connection analytics (local source changes)

These uncommitted changes modify the core working tree and parent facade/Android consumers, not the retained
libXray, tunnel or helper forks. Versions remain Xray 26.10.31 / Go 1.27.3. Rebuild the core AAR from these sources
before Android compilation; an old AAR lacks `ConnectionSnapshot` and `ClearConnectionHistory`. The owner must
publish the modified core fork and update the parent gitlink before clean-checkout CI or release publication.

`lib.Start` installs a per-instance `DefaultDispatcher.SetConnectionObserver` before starting listeners.
Validation and bare ping instances do not install it. Observation starts before sniffing/routing and captures the
selected handler after route selection, never a mutable session pointer or the current Android selection.
`_yaxcConnectionProfiles` maps logical outbound tags to captured profile ID/name/fingerprint and actual protocol
route kind. Runtime comparison ignores display-name-only changes; IDs/fingerprints/routes remain real inputs.
Ordinary profiles provision UID accounts independently of Live Routing and never read its saved plan.

The facade binds new physical tags before router publication, retaining old identities for established flows.
Identity-only changes get aliases rather than reusing a mutable tag binding. Rule-local immutable alias maps
translate stock Observatory/fallback balancer results while preserving logical tags for health lookup.
There are at most 4,096 identity-only aliases per core; exhaustion requests explicit reconnection rather than
relabeling traffic or restarting silently. Tracker capacity does not limit valid outbound configuration counts.

Concrete pipe hooks preserve mux's reader-type behavior and cached sniffing counts. Observed writer adapters
preserve timeout, close/interrupt, endpoint and native partial-write accounting. `common/task.Run` retains each
actual task lifetime, including early error/cancellation returns; mux, chained/detached work and splice have
matching ownership. TimeoutOnly contexts retain observation without changing their detached cancellation semantics.
Splice is not disabled: blocking raw-copy download bytes are credited at completion and marked deferred beforehand.
Opaque writer failures with unknowable partial delivery permanently mark totals incomplete. These are logical
TCP/UDP proxy payload bytes, not IP packets, provider quota or outer encrypted transport totals.

UDP observations are associations with up to eight observed destinations; they do not change cone/routing
semantics or claim exclusive/per-destination totals. Managed/stock Observatory and internal DNS contexts explicitly
disable observation. Source buffers/payloads, credentials, raw configs and raw diagnostics never enter the model.

Each core owns its bindings; one process-lifetime history/epoch supplies globally unique observation IDs across
core restarts. It retains at most 2,048 active/finished records; closed rows expire after 24 hours, and active
saturation drops observations, never forwarding. Stop marks unfinished rows interrupted/incomplete without
discarding handles, so late byte/finish callbacks survive immediate core replacement. Snapshot reads do not take
`lifecycleMu`. `ClearConnectionHistory` removes only closed metadata, not active counters or traffic.

Android samples on IO once per second, with at most one pending snapshot per runtime and a bounded stopped tail.
It captures package identities independently of UI lifetime. UID reuse/shared-membership changes stay unknown for
the app process; TUN admission reports uncertainty before the corresponding SOCKS flow can open, without changing
the pre-existing UID-based routing decision. Existing historical app labels are never reassigned. The existing
SOCKS-credential impersonation and experimental admission-policy limits above still apply. SOCKS-only/no-auth and
older Android owners remain unknown; the separately supplied root executable has no observer bridge.

Native race regressions cover real TCP/UDP payloads, retained streams, metadata-only and stock-balancer alias
updates, large configurations, partial writers, half-close, async/mux/splice lifetime and internal-probe exclusion.
DNS tests use local HTTPS/TCP/QUIC peers instead of public availability; certificate verification remains enabled.
`NewQUICNameServer` takes an explicit copied TLS configuration for controlled trust dependencies; the normal factory
passes nil and retains its existing default trust behavior. Periodic cadence tests use a virtual clock rather than
racy wall-clock counters. Actual Android UI/page-size evidence belongs in the current project state, not old CI results.

