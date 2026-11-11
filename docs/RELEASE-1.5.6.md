# proxide 26.11.9.2 → yaxc 1.5.6

Prepared versions: proxide **26.11.9.2**, yaxc **1.5.6 / 1178**. No commits, tags or publication are part of preparation.

Release notes:

- Core: [`XrayCore/Xray-core/CHANGELOG.md`](../XrayCore/Xray-core/CHANGELOG.md).
- App: [`metadata/en-US/changelogs/1178.txt`](../metadata/en-US/changelogs/1178.txt). Published `1177.txt` is unchanged.

The four required app signing secret names are present in GitHub; their values and production signing have not been exercised during preparation.

Run the following blocks manually, in order, from the yaxc root, using Bash. They **commit, push and publish**. Review all staged changes; exclude unrelated work. `set -e` stops at a failed gate; do not bypass it or force-push.

## 1. Commit and integrate the core

The working changes are on `feat/safe-analytics-stop`. The release commit must be reachable from published `fork/main`; `main` stays on upstream Xray 26.11.9.

```bash
set -euo pipefail
(
  cd XrayCore/Xray-core
  test "$(git branch --show-current)" = feat/safe-analytics-stop
  git diff
  git add -A
  git diff --cached
  git commit -m "Safely stop connection analytics during active forwarding"
  git fetch origin
  git switch fork/main
  git merge --ff-only origin/fork/main
  git merge --no-ff feat/safe-analytics-stop -m "Merge safe connection analytics cutoff"
  test "$(go run .github/build/source-version.go)" = 26.11.9.2
  git push origin fork/main
  git tag -a v26.11.9.2 -m "proxide 26.11.9.2"
  git push origin refs/tags/v26.11.9.2
)
```

A merge conflict requires resolution and fresh verification before proceeding. Do not discard either branch. Pushing the tag starts the core release workflow: source/ancestry checks, tests, platform archives, checksums and Docker publication. Do not create a public Release with `gh release create`.

## 2. Wait for the core release and attach the prepared notes

Run after the tag-triggered workflow appears. If the run ID is empty, wait for GitHub to register the push and rerun this block; never select an unrelated run.

```bash
set -euo pipefail
CORE_RUN_ID=$(gh run list --repo derundevu/proxide --workflow release.yml \
  --branch v26.11.9.2 --event push --limit 1 --json databaseId --jq '.[0].databaseId // empty')
test -n "$CORE_RUN_ID"
gh run watch "$CORE_RUN_ID" --repo derundevu/proxide --exit-status
gh release view v26.11.9.2 --repo derundevu/proxide
```

The workflow generates upstream/full-diff/commit/Docker provenance from committed history. Preserve it when adding the human-written changelog, **after successful publication**. Run this block once, otherwise the changelog would be prepended again:

```bash
set -euo pipefail
CORE_NOTES=$(mktemp)
{
  cat XrayCore/Xray-core/CHANGELOG.md
  printf '\n---\n\n'
  gh release view v26.11.9.2 --repo derundevu/proxide --json body --jq .body
} > "$CORE_NOTES"
gh release edit v26.11.9.2 --repo derundevu/proxide --notes-file "$CORE_NOTES"
rm "$CORE_NOTES"
```

Do not overwrite published assets or versioned images. Core CLI archives are not yaxc's AAR.

## 3. Pin the published core and commit the app

The four-part tag is not a Go-module semantic version. Resolve its commit through the **fork** repository, retain the `github.com/xtls/xray-core` module/import path and local replacement, and use the resolved pseudo-version. Do not query the unpublished fork commit through the upstream repository.

```bash
set -euo pipefail
CORE_SHA=$(git -C XrayCore/Xray-core rev-parse 'v26.11.9.2^{commit}')
test "$(git -C XrayCore/Xray-core rev-parse HEAD)" = "$CORE_SHA"
CORE_MODULE_VERSION=$(
  cd XrayCore
  GOPROXY=direct go list -m -f '{{.Version}}' "github.com/derundevu/proxide@$CORE_SHA"
)
(
  cd XrayCore
  go mod edit -require="github.com/xtls/xray-core@$CORE_MODULE_VERSION"
)
printf 'Core commit: %s\nGo pseudo-version: %s\n' "$CORE_SHA" "$CORE_MODULE_VERSION"
```

Before committing, update the preparation paragraphs in `docs/NATIVE.md` and `docs/DEVELOPMENT.md` to record the published core SHA/pseudo-version and remove the pending-publication wording. Keep historical verification labeled as historical. The pseudo-version and parent gitlink must identify the same commit. No new wrapper/helper/tunnel revision is required.

```bash
set -euo pipefail
test "$(git branch --show-current)" = master
git diff
git add -A
git diff --cached
git commit -m "Release yaxc 1.5.6 with proxide 26.11.9.2"
APP_SHA=$(git rev-parse HEAD)
git push origin master
printf 'App revision for CI and tag: %s\n' "$APP_SHA"
```

Ignored local AAR/APKs are not release inputs; CI rebuilds from the published submodule sources. Do not force-add old native artifacts. Do not run `git submodule update` over uncommitted core work.

## 4. Require CI on this exact app commit, then publish

The app release workflow **does not wait for CI**. Wait until the push CI run appears, then require its successful completion, including the emulator jobs. Run in the same shell as step 3 so `APP_SHA` remains the reviewed commit. If any source changes, commit/push again and use the new SHA and its CI run instead.

```bash
set -euo pipefail
test "$(git rev-parse HEAD)" = "$APP_SHA"
APP_CI_ID=$(gh run list --repo derundevu/yaxc --workflow ci.yml \
  --commit "$APP_SHA" --event push --limit 1 --json databaseId --jq '.[0].databaseId // empty')
test -n "$APP_CI_ID"
gh run watch "$APP_CI_ID" --repo derundevu/yaxc --exit-status
test "$(gh run view "$APP_CI_ID" --repo derundevu/yaxc --json headSha --jq .headSha)" = "$APP_SHA"
git tag -a v1.5.6 "$APP_SHA" -m "yaxc 1.5.6"
git push origin refs/tags/v1.5.6
```

The tag immediately starts production signing and publication. Required repository secrets: `KS_FILE`, `KS_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The workflow requires `v1.5.6` to match Gradle `versionName` and reads notes from `1178.txt`.

```bash
set -euo pipefail
APP_RELEASE_ID=$(gh run list --repo derundevu/yaxc --workflow release.yml \
  --branch v1.5.6 --event push --limit 1 --json databaseId --jq '.[0].databaseId // empty')
test -n "$APP_RELEASE_ID"
gh run watch "$APP_RELEASE_ID" --repo derundevu/yaxc --exit-status
gh release view v1.5.6 --repo derundevu/yaxc
```

Expected assets: `yaxc-v1.5.6-{armeabi-v7a,arm64-v8a,x86,x86_64,universal}.apk`, all with versionCode **1178**. The workflow publishes only after all five signed builds succeed.

## Verification boundary

The implementation session verified 559 unit tests, lint without errors, five distinct Android scenarios on ARM64/API 36/4 KiB, targeted native race checks and real TCP/UDP continuity across analytics Off/On. These are prior implementation results, not a new full release run; only ARM64 artifacts were rebuilt there. They do not establish production signing, all-ABI execution, 16 KiB runtime compatibility or battery savings.

This preparation exercised the source-version command (`26.11.9.2`) and Gradle manifest processing (`1.5.6 / 1178`), and resolved the published baseline's fork pseudo-version. Existing AAR/APKs still carry their earlier build versions; the release workflows must rebuild them. No remote release command above was executed during preparation.
