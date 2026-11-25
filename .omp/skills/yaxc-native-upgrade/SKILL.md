---
name: yaxc-native-upgrade
description: Safely upgrade yaxc native dependencies, customized Xray/libXray forks, Go/gomobile, helpers and HEV tunnel submodules. Use for upstream/security comparisons, preserving dirty fork changes, schema/module alignment, ABI packaging, isolated regression gates and owner publication handoffs.
---

# yaxc native dependency upgrades

Use this for a native/dependency migration or release-readiness audit, not an automatic refresh to every dependency's latest version. All file paths below are **repository-root-relative**; run commands from the yaxc root. Read `AGENTS.md`, `PROJECT_STATE.md`, `docs/NATIVE.md` and the applicable sections of `docs/DEVELOPMENT.md`. Code, locked build inputs, CI and current primary upstream sources override historical versions in this skill or old reports.

## Inputs and boundaries

Establish the requested component/target, current upstream base and fork revision, local changes to retain, supported server/config classes, required ABIs, available isolated test environments and publication permissions. Recover obtainable information from the repository and official upstream; do not ask the owner to repeat it. Resolve unspecified upgrade scope from the task, not an archived plan.

- Never create commits, tags, pushes or releases, rewrite history, reset/clean a checkout, use submodule `--remote`, or change global machine configuration. Prepare source changes and an owner handoff instead. An invocation does not override these restrictions.
- Preserve existing staged, unstaged and untracked work. Never infer that a dirty submodule is disposable or that the parent gitlink contains its working-tree changes.
- Do not uninstall, clear application data, replace subscriptions/profiles, change accounts/settings, enable root or alter the owner's VPN. Use disposable synthetic fixtures. Device installation/launch requires explicit authorization; stop on a signature conflict rather than bypassing it.
- Keep configs, tokens, keys, authorization headers, keystores, private endpoints, account details and device serials out of tracked files and reports. Use public HTTPS fork URLs, never workstation SSH aliases.
- Delegate disjoint source slices only. One integration owner runs authorized verification after all changes land; workers must not build/test/lint/format mid-flight. If verification is not authorized, provide the exact checks and mark them unrun.

## 1. Inventory and preserve before changing sources

```bash
git status --short --branch --untracked-files=all
git submodule status --recursive
git submodule foreach --recursive 'git status --short --branch --untracked-files=all'
```

Record each repository's HEAD, branch/detached state, parent gitlink, upstream base, sanitized remote, staged/unstaged changes, untracked candidate files and any pending merge/conflicts. `-`, `+` and `U` in submodule status mean uninitialized, gitlink mismatch and conflict; a clean status alone proves none of those are resolved. Do not initialize/update over local work. An uninitialized dependency is an explicit inventory gap.

Before switching or merging, make a source backup outside the edited source set (an ignored artifact directory is suitable). Include every recursive repository, binary-capable staged and unstaged diffs, copies of untracked candidate sources, and the revision/gitlink/merge-state inventory. A diff alone omits untracked files; a parent archive alone can omit submodule contents. Keep sensitive backups private and record a checksum plus file/repository counts, not their contents. Confirm recoverability before destructive experimentation; do not use reset/restore as a migration technique.

Ownership follows the checkout, not the component's informal name:

| Source | Owner / consumer |
| --- | --- |
| `XrayCore/lib`, root `XrayCore/*.go`, `XrayCore/go.mod`/`go.sum` | Parent yaxc facade/build module, not the libXray submodule |
| `XrayCore/Xray-core` | Customized core fork consumed by the root module |
| `XrayCore/libXray` | Retained wrapper fork; separate history and module metadata |
| `XrayHelper` | Helper fork, not the separately supplied root-mode Xray executable |
| `app/src/main/jni/hev-socks5-tunnel` | Tunnel fork, with recursively pinned dependencies |
| Android callers, Gradle/root build scripts and app tests | Parent yaxc; keep coherent with JNI implementations/declarations in the tunnel fork |

## 2. Select an exact upstream and assess the real delta

Use official release notes, security advisories, repository tags, commit/compare APIs and source diffs, not a third-party summary or mutable branch HEAD. Resolve the selected tag to its full commit SHA and distinguish annotated-tag signature from commit signature. Check available cryptographic signatures against trusted provenance or primary-host verification metadata; state the verification method and any unsigned/unverifiable source. Never describe an unsigned tag as signed because its commit is verified.

Compare **old upstream → exact target** separately from **old upstream → current fork plus dirty candidate**. Record the meaningful commits, affected schema/APIs/platforms, required Go/dependency changes and security urgency. Inspect listener versus dialer defaults, Android exposure, explicit opt-ins and OS prerequisites. Distinguish application mitigation from a kernel fix; a withdrawn release is not a safe default merely because old tests passed. Review actual interoperability/config changes rather than equating a newer release with a new protocol or mandatory key conversion.

Make a short local-change ledger: owning repository, behavior/invariant, target-upstream overlap and regression evidence. Retain every existing local fix and feature. Remove/supersede one only when equivalent upstream behavior is demonstrated and the task permits it; keep the regression. Do not implement a broad fix on the old base before checking the target. The historical .21 migration withdrew a 29-file refactor, then retained narrowly proven constructor cleanup and synchronized splice state.

## 3. Port narrowly; preserve additive fork history and contracts

Prepare the migration without rewriting the existing fork lineage or committing. Preserve the dirty candidate first; use a separate candidate workspace when needed. The owner's eventual additive merge must retain the previous customized fork and exact upstream target as ancestry, not replace the fork with an upstream snapshot. A prepared tree or pending merge is not a published commit. If a safe merge requires an owner-created checkpoint, identify that prerequisite instead of bypassing the no-commit rule.

Inspect the ledger across all callers: failed-construction resource cleanup; atomic splice transitions and independent metadata snapshots; facade lifecycle/ownership; isolated validation; ping GET/HEAD/TCP cancellation/deadlines and worker-slot release; live routing/candidates, tagged probes/cancellation and SOCKS account snapshots where present. Preserve tunnel owner checks, start/stop/join semantics, borrowed descriptors and JNI signatures. Do not disable splice, skip a failing test or suppress a runtime error to make an upgrade appear safe.

- Retain the app's facade/file-path/Base64 API unless an API cutover is explicitly requested. A modern libXray Invoke redesign is not a drop-in upgrade. Migrate all consumers if a contract really changes; leave no obsolete aliases or no-op compatibility paths.
- Merge source schemas and generated protobuf output together. Account for new fields and generator/runtime versions; regenerate affected output only with the owning project's pinned/documented tools. Do not resolve only `.pb.go` conflicts while dropping schema fields. Historical examples include XHTTP session-key adapters and the later `h2Params` schema/generated-code addition; inspect the selected target rather than prescribing those as new work forever.
- Align root, core and retained-wrapper `go.mod`/`go.sum`, effective selected module graph, target metadata and any standalone builder source selector. Go pseudo-version prefixes are not runtime-version evidence. Keep canonical module/import paths despite fork remote URLs and retain local `replace` directives. Avoid unrelated dependency churn.
- Select Go and gomobile/gobind from the locked root graph, respecting the core/helper requirements. Do not use `@latest`, optional script setup/refresh modes or `gomobile init` for routine builds: the historical .29 failure showed `init` reinstalling `gobind@latest` over the pin.
- Inspect generated app configurations and import/export round trips, not just native compilation. Preserve supported fields/profiles; unsupported removed features must fail explicitly, not silently yield a successful subset. Do not rewrite stored raw configs, keys or subscriptions as an upgrade shortcut.

## 4. Rebuild the source-matched artifact set

Read current pins from Gradle, module files, scripts and CI. Record the actual Go/tool versions, JDK, SDK/build-tools, NDK, gomobile/gobind and requested ABI set. Verify downloaded source/tool checksums/signatures where supplied. Match `JAVA_HOME`, `ANDROID_HOME` and any `ANDROID_SDK_ROOT`; use the Gradle wrapper, not machine-specific convenience/install scripts.

For an authorized all-ABI packaging build, the current entry points are:

```bash
bash -e ./buildXrayCore.sh all &&
bash -e ./buildXrayHelper.sh all &&
./gradlew :app:assembleDebug
```

Single-ABI native arguments map as `arm` → `armeabi-v7a`, `arm64` → `arm64-v8a`, `386` → `x86`, `amd64` → `x86_64`; pass the matching `-PabiTarget` to Gradle. Native builds replace the AAR/helper asset set. After emulator-only builds, rebuild for the phone ABI or `all`; never relabel an x86_64 artifact as ARM64.

Rebuild the core/wrapper AAR, helper and NDK-built tunnel/JNI/APK from the affected sources. Require `:app:verifyNativeArtifacts` through `preBuild`; never disable it. Inspect the actual archives and ELF machine types: each selected AAR ABI needs nonempty `jni/<ABI>/libgojni.so`, each APK needs its matching Go/tunnel libraries and `assets/xrayhelpers/<ABI>`. A universal APK must contain all selected ABIs; inspect splits too. Check version name/code, runtime core version and source/tool/artifact hashes. A successful Gradle task alone previously produced an ARM64 APK missing `libgojni.so` after an emulator build, causing startup `UnsatisfiedLinkError`.

Check ELF LOAD alignment/congruence and RELRO ends, plus APK `zipalign -c -P 16 4`, using the selected build-tools/NDK utilities. Verify APK signatures/certificate identity with `apksigner`; a debug-signed release smoke is not production signing evidence. Static alignment is not proof of actual 16 KB loading, especially for bundled third-party graphics libraries.

## 5. Run authorized isolated regression gates, not a startup-only smoke

Use the current documented/CI commands and selected Go toolchain; no blanket environment/toolchain upgrades. Representative required host gates from the root are:

```bash
go -C XrayCore test -mod=readonly -count=1 -timeout=90s ./... &&
go -C XrayCore test -mod=readonly -race -count=1 -timeout=240s . ./lib &&
go -C XrayCore test -mod=readonly -count=1 -timeout=30s github.com/xtls/libxray/nodep &&
go -C XrayCore/Xray-core test -mod=readonly -race -count=1 -timeout=90s ./proxy ./common/mux ./proxy/socks ./proxy/http ./proxy/loopback &&
go -C XrayCore test -mod=readonly -race -count=1 -timeout=240s \
  github.com/xtls/xray-core/app/observatory github.com/xtls/xray-core/app/router \
  github.com/xtls/xray-core/app/proxyman/outbound github.com/xtls/xray-core/proxy/socks \
  github.com/xtls/xray-core/core github.com/xtls/xray-core/common/session \
  github.com/xtls/xray-core/transport/internet
```

Keep process-global facade tests non-parallel; use the documented shuffled lifecycle/cancellation race run when relevant. Add the selected target's focused regressions, including changed security defaults and explicit opt-in behavior. Exercise real loopback proxy payloads and representative old/new peer/config classes before/after validation, reload and Stop. Include FD/listener cleanup, blocked/failed protocol writes, chained cancellation and retained streams; for routing changes cover health/candidate failure, all-down refusal and no unintended default/direct traffic.

With matching native artifacts, run full JVM/lint gates, not only a narrowed new test:

```bash
./gradlew -PabiTarget=x86_64 --continue :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

For Android, use a dedicated disposable **x86_64** emulator and the isolated `.regression` application, never the owner's installed app. Select `EMU_DEVICE` explicitly from the dedicated emulator inventory; confirm it is that emulator, its ABI and measured page size before acting. Run the full API/page-size matrix from current CI (4 KB and 16 KB); a different API or 4 KB run does not replace it. With authorized device verification:

```bash
adb -s "$EMU_DEVICE" shell getprop ro.product.cpu.abi
adb -s "$EMU_DEVICE" shell getconf PAGE_SIZE
bash -e ./buildXrayCore.sh amd64 &&
bash -e ./buildXrayHelper.sh amd64 &&
./gradlew -PabiTarget=x86_64 :app:assembleRegression :app:assembleRegressionAndroidTest
```

Only after that build succeeds, separately install those **fresh** matching APKs and run:

```bash
adb -s "$EMU_DEVICE" install -r app/build/outputs/apk/regression/app-x86_64-regression.apk &&
adb -s "$EMU_DEVICE" install -r app/build/outputs/apk/androidTest/regression/app-regression-androidTest.apk &&
PAGE_SIZE=$(adb -s "$EMU_DEVICE" shell getconf PAGE_SIZE) &&
adb -s "$EMU_DEVICE" shell am instrument -w -r \
  -e allowVpn true -e expectedPageSize "$PAGE_SIZE" \
  io.github.derundevu.yaxc.regression.test/io.github.derundevu.yaxc.testing.RegressionTestRunner
```

Require successful installation and final `OK (...)` with zero failures; instrumentation's shell exit code alone is insufficient. Stop if either install fails. Cover real native loading, helper extraction, validation/ping isolation and VPN lifecycle/TCP/UDP/DNS/IPv6/owner-policy where applicable. Respect the fixture's new-owned-VPN route-ready barrier, not a delay or service-start acknowledgement. Exercise the changed workflow through the **actual activity/UI** with synthetic data; a test-only screen or launch alone is not functional proof.

A host race pass, emulator pass, ARM64 cold launch, physical ARM/ARM64 VPN, root mode and ARM64 16 KB loading are distinct claims. Root's separately supplied Xray is not updated by an AAR/helper build. Record unavailable coverage explicitly. Historical upstream TLS/OCSP races and protobuf copylocks were not permission to suppress failures or claim a whole upstream suite passed. A failed/hung required gate remains a release blocker; preserve its evidence and diagnose without weakening the test. Never install a previous APK after any build failure.

When an expanded upstream suite relies on public DNS/HTTPS, replace that uncontrolled fixture with a real local peer while preserving its behavior checks, not with a skip or weakened assertion. ECH coverage must prove accepted encrypted handshakes, concurrent cache behavior and no HTTP delivery on missing/malformed config. Certificate/OCSP reload must publish immutable snapshots: locking selection alone is insufficient because the TLS handshake keeps reading after the callback returns. Keep watcher inputs private and test retained snapshots, invalid-reload rejection and real replacement handshakes; distinguish untested CA-issuance paths.

Fixture observation queues must not become accidental health/network backpressure during a longer race run. Keep controlled manual probes reliable; let unused automatic observations drain or drop without blocking the synthetic peer. Prove pressure behavior separately and retain the original concurrent traffic/hot-apply assertions rather than increasing timeouts or silently narrowing the gate.

## 6. Reproducibility and owner publication handoff

Publishability is a dependency graph, not “update the parent SHA”:

1. Owner publishes modified nested leaves (for example tunnel `third-part/lwip` and `src/core`) to **existing, accessible forks**.
2. Owner publishes the tunnel with real leaf URLs/gitlinks and its source changes. Independently, publish customized core, then aligned retained libXray when required; publish changed helper sources too.
3. Parent pins only those published commits and includes its facade, Android/JNI callers, tests and module graph as one coherent change.
4. Require a fresh recursive checkout and hosted CI on that exact parent revision, including both page-size jobs, before owner release publication. Tag-triggered release workflows do not necessarily wait for CI.

Check actual repository existence, read availability and publication rights separately; pull access does not imply push access. Do not invent fork URLs, gitlink SHAs or fake clean-checkout reproducibility. If publication is unavailable, leave working sources intact and hand off the exact missing leaf repositories/permissions, per-repository files and additive-merge prerequisites, publication order, enclosing URL/gitlink updates, and the checks blocked on them. Do not substitute an uncommitted local HEAD or patch-file build path for published source.

Update `docs/NATIVE.md`, affected `docs/DEVELOPMENT.md` instructions, `PROJECT_STATE.md` and the current `metadata/en-US/changelogs/` entry when the upgrade task includes those files; otherwise give their owner precise changes. Preserve already published notes. Keep current version/code and release workflow naming aligned with actual code, not historical ABI offsets. Write English, record exact source revisions and observed results/limits, and distinguish **prepared locally**, **published/pinned**, **fresh-checkout verified**, **production signed** and **released**. Return blockers first, changed repositories/invariants, artifact provenance, checks actually run and the owner's next action. Never claim green old-version results validate the new target.
