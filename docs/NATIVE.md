# Native dependencies and forks

Read this only for native code, dependency or build changes. Commands run from the repository root;
see [README.md](../README.md#build) for toolchain requirements and ABI-specific builds.

## Sources and versions

All four direct submodules already use `derundevu` forks. [`.gitmodules`](../.gitmodules) defines URLs;
parent-repository gitlinks pin exact commits, not the latest fork branch HEAD.

| Submodule | GitHub fork | Consumer / output |
| --- | --- | --- |
| `XrayCore/Xray-core` | `derundevu/Xray-core` | Go core in `app/libs/XrayCore.aar` |
| `XrayCore/libXray` | `derundevu/libXray` | Wrapper in the same AAR |
| `XrayHelper` | `derundevu/XrayHelper` | `app/src/main/assets/xrayhelper`, root runtime |
| `app/src/main/jni/hev-socks5-tunnel` | `derundevu/hev-socks5-tunnel` | tun2socks/JNI, built by NDK through Gradle |

The tunnel's nested submodules have their own URLs and pinned commits; they were not all moved to these forks.
[`XrayCore/go.mod`](../XrayCore/go.mod) replaces `github.com/xtls/xray-core` and `github.com/xtls/libxray`
with local directories. Do not rename module paths/imports just because the Git remote is a fork.

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
3. Check the existing compatibility patches below against the chosen revision. Do not duplicate each new fork fix in a patch file.
4. Rebuild the affected AAR/helper for the target ABI after Go/source/version changes, or JNI/APK after tunnel changes.
   Existing artifacts do not prove that new native sources build.
5. Verify a clean checkout build and the changed runtime scenario. Obtain approval before interrupting the user's VPN or root runtime.

## Remaining patch flow

Fork migration did not remove these files or their build consumers:

| Patch | Consumer |
| --- | --- |
| [libXray compatibility](../XrayCore/patches/libxray-xray-core-26.3.27.patch) | [`buildXrayCore.sh`](../buildXrayCore.sh) before gomobile bind |
| [Tunnel hardening](../app/src/main/jni/patches/hev-socks5-tun0-defense.patch) | [`applyHevTunnelPatch.sh`](../applyHevTunnelPatch.sh), explicitly called by [CI](../.github/workflows/release.yml) before Gradle |

Both scripts apply a patch when `git apply --check` succeeds, accept an already-applied patch when
`git apply --reverse --check` succeeds, and fail if neither check succeeds. Gradle alone does not run the tunnel patch script.
Read-only checks for already-included changes:

```bash
git -C XrayCore/libXray apply --reverse --check "$PWD/XrayCore/patches/libxray-xray-core-26.3.27.patch"
git -C app/src/main/jni/hev-socks5-tunnel apply --reverse --check "$PWD/app/src/main/jni/patches/hev-socks5-tun0-defense.patch"
```

If a reverse-check fails, try `apply --check` without `--reverse`: success means the patch can still be applied;
failure of both checks means incompatibility, not an error to suppress.
Remove patch files and their script/CI consumers together only after a clean build proves the fork revisions contain the required changes.
Tun hardening remains experimental, not a system Kill Switch guarantee.
