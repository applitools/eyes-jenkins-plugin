# Plan: Chromatic-style visual review for Jenkins + Applitools Eyes

## Goal

Install the plugin, wrap the tests in one step, and get the Chromatic experience on GitHub PRs:

1. A developer opens or updates a PR, and Jenkins runs the Eyes tests automatically.
2. The PR gets an **Applitools / Visual tests** check with a link to the diffs.
3. If the PR is flagged for design review, it also gets an **Applitools / Design review** check.
4. The designer reviews in Eyes and accepts or rejects. They never need GitHub or Jenkins.
5. Branch protection blocks the merge until both checks pass.

```groovy
// The whole developer-facing setup
pipeline {
  agent { label 'playwright' }
  stages {
    stage('Visual tests') {
      steps {
        applitools {
          sh 'npx playwright test'
        }
      }
    }
  }
}
```

## Key design decisions

**Jenkins owns the GitHub checks, not Eyes.** Eyes' built-in GitHub status only knows "all diffs resolved." It can't express an optional designer gate or who approved. The plugin posts its own checks through Jenkins' Checks API (`checks-api` + `github-checks`), which the sandbox already proves works.

**Batches are keyed to the PR's head commit.** Multibranch PR builds may check out a synthetic merge commit. The plugin reads the real head SHA from the build's SCM revision, so Eyes results, checks and the PR all refer to the same commit.

**The build doesn't wait for the designer.** Review can take days. The build finishes, and a background watcher in the plugin follows the batch and updates the Design review check when the designer acts. No executor is held.

## Approval gates

Two checks, mirroring Chromatic's "UI Tests" and "UI Review":

| Check | State | When |
| --- | --- | --- |
| **Applitools / Visual tests** | ⏳ in progress | Tests running |
| | ✅ success | Every visual step passed, or all changes were accepted (by anyone) |
| | ⚠️ action required | N changes are unresolved; the "Review in Eyes" link goes to the batch |
| | ❌ failure | Tests crashed, nothing was uploaded, or a change was rejected |
| **Applitools / Design review** | ➖ skipped | PR isn't flagged for design review (GitHub counts this as passing) |
| | ⏳ in progress | Flagged; waiting for a designer |
| | ✅ success | Flagged, and a designer accepted every change (or there were none) |
| | ❌ failure | A designer rejected a change ("Changes requested") |

The Design review check is **always posted** once the feature is on, even when it's skipped. A required check that never reports would block every PR.

**How a PR gets flagged**, highest precedence first:
1. `applitools(designReview: true | false)` in the Jenkinsfile
2. `APPLITOOLS_DESIGN_REVIEW=true` in the environment
3. A PR label, default `design-review` (configurable)
4. Global default (off)

**Who counts as a designer:** a list of Eyes users or an Eyes team, set globally or per folder. A developer accepting their own diffs satisfies Visual tests but not Design review. If Eyes can't tell us who accepted a change, v1 treats any acceptance as approval.

**Label added after the tests ran:** the plugin listens for GitHub's `labeled`/`unlabeled` webhooks and flips the Design review check (skipped ↔ waiting) without re-running the tests.

## Chromatic parity

| Chromatic | This plugin | Phase |
| --- | --- | --- |
| Runs on every push / PR | GitHub Branch Source triggers the build; `applitools {}` sets up Eyes | ✅ 1 |
| Baselines follow git branches | Branch and parent-branch names derived from the PR | ✅ 1 |
| UI Tests check | Applitools / Visual tests | 3 |
| UI Review check with reviewers | Applitools / Design review, label-flagged, designer list | 4 |
| Review link on the PR | The check's Details link opens the Eyes batch; optional summary comment | 3 |
| Accepted baselines merge on PR merge | Merge the Eyes branch baseline when the PR merges | 5 |
| `--auto-accept-changes main` | `autoAcceptOn: 'main'` | 5 |
| `--exit-zero-on-changes` | `onChanges: 'UNSTABLE' \| 'FAILURE' \| 'SUCCESS'` | 2 |
| `--exit-once-uploaded` | `waitForResults: false` | 2 |
| Build history in the web app | Build page summary, per-job trend | 2 |
| TurboSnap / only changed stories | Out of scope (Eyes runs what the tests run) | — |

**Beyond Chromatic:**
- Labeling a PR after tests ran updates the gate without a re-run.
- The designer gate checks *who* approved, not just that someone did.
- Multiple parallel stages and agents share one batch (`applitools { parallel … }`). ✅
- Cross-browser and device rendering via the Ultrafast Grid, with no extra setup.
- Setup checklist and Test connection button in Manage Jenkins.

## Steps the plugin provides

| Step | Purpose | Phase |
| --- | --- | --- |
| `applitools(…) { … }` | Sets up Eyes env, closes the batch, collects results, posts checks | ✅ 1 (checks in 3) |
| `applitoolsResults()` | Returns `[status, passed, unresolved, failed, new, url]` for the build's batch, for custom notifications or logic | 2 |
| `applitoolsAwaitReview(timeout: '2h')` | Waits, without holding an executor, until the review is resolved. For deploy pipelines that must not continue until approval. | 4 |

`applitools` options: `credentialsId`, `serverUrl`, `batchId`, `batchName`, `branchName`, `parentBranchName`, `closeBatch` (✅ now); `onChanges`, `waitForResults` (2); `checks` (3); `designReview`, `designReviewLabel`, `designers` (4); `autoAcceptOn` (5).

## Phases

| Phase | Scope | Status |
| --- | --- | --- |
| **0. Sandbox** | GitHub App, smee relay, signed webhooks, multibranch job, Checks API posting | ✅ Done |
| **1. Run** | `applitools {}`: credentials, masking, SCM detection (GitHub + env fallback), batch close, nested/parallel | ✅ Verified on a real PR |
| **2. Results** | Fetch batch results (read key), build page summary and link, `applitoolsResults()`, `onChanges` policy, `waitForResults` | ✅ Verified on a real PR |
| **3. Visual tests check** | Post the check from results, Details link to Eyes, optional PR comment | ✅ Verified on a real PR (PR comment not built) |
| **4. Design review gate** | Flag detection, Design review check, background watcher, label webhooks, designer identity, `applitoolsAwaitReview` | ✅ Verified live: label webhook, watcher flipping both checks after accept in Eyes, restart survival. Designers-only path verified against real API data, not yet end to end. `applitoolsAwaitReview` not built |
| **5. Baseline lifecycle** | Merge branch baselines on PR merge, `autoAcceptOn` | |
| **6. Setup experience** | Test connection, setup checklist page, JCasC, sample Jenkinsfiles, 5-step guide | |
| **7. Hardening** | Fork PRs, watcher timeouts and restarts, dedicated/on-prem Eyes, GitHub Enterprise | |

## Open questions about the Eyes API

1. **Batch results:** the endpoint and shape for a batch's summary (counts by status, per-test status, batch URL) by pointer ID.
2. ~~**Who accepted:**~~ Answered: `GET /api/sessions/batches/{batchId}/{sessionId}` → `appOutputResolution[].lastAccept {user, sessionId}` once resolved (`expectedAppOutput` is the run-time snapshot and names earlier sessions); `isManuallyRejected` for rejections.
3. **Review events:** are there webhooks for accept/reject, so the watcher can stop polling?
4. **Branch-name format:** is `owner/repo/branch` what Eyes expects for branch baselines and merging?
5. **Baseline merge:** the API to merge a branch baseline into its parent (Phase 5).
6. **Auth header:** can the API key go in a header instead of the `apiKey` query parameter?
7. **Re-runs:** what happens when a closed batch's pointer ID is reused by a re-run of the same commit?

## Parked: come back after the MVP

### Eyes NLP hangs the Playwright SDK in our Podman agent (found 2026-10-20)

**Symptom:** with NLP enabled on the account, `eyes-playwright` launches Chromium with a remote-debugging port. The test passes, but no checkpoint reaches Eyes: every Ultrafast Grid session is aborted with 0 steps. The worker then fails with `RequestTimeoutError: Request timed out, likely because it was stuck` (from `normalizeAbortError` in `@applitools/req`).

**Isolated:** fails 3 of 3 times with NLP on and passes the first time with `eyesConfig.nlpOptions.enabled: false`. Memory (2 GB vs 6 GB) and network ruled out.

**Workaround in place:** the sandbox repo sets `nlpOptions: { enabled: false }` (commit `b1c6309` on `test-fire`).

**Leading theory:** container-specific. The Podman agent may lack what NLP's remote-debugging attach needs (sandboxing flags, `/dev/shm` size, reachable debugging port). Check before reporting to R&D:
1. Run the same test on macOS with NLP on. Does it pass?
2. If yes, retry in the container with `--shm-size=1g` and/or `--ipc=host`, and check Chromium's sandbox flags.
3. Report to R&D with the result.

**Details for R&D:** `@applitools/eyes-playwright` 1.51.2, `@applitools/core` 4.73.0, `@applitools/req` 1.11.5, `@playwright/test` 1.64.1, Node 22.23. Ultrafast Grid: Chrome, Firefox, Safari, iPhone 15, `fully: true`. Failing batches `00000251609776582785`, `00000251609775668285`, `00000251609775028435`; passing batch `00000251609774741653`.

**Plugin follow-up:** if NLP needs particular container setup, document it in the setup guide (Phase 6).

### Several `applitools` blocks in one build share a batch ID

Each block defaults its batch ID to the commit SHA. A second block in the same build reuses that ID after the first block closed the batch, so Eyes starts a new batch under the same pointer, and the second block's results and review entry replace the first's. **Workaround (documented in SETUP.md):** put all visual tests in one block. **Fix:** suffix the batch ID per block (e.g. `<sha>-2`), and keep one review entry per block.

## Not in scope (for now)

- A review UI inside Jenkins (designers review in Eyes).
- GitLab / Bitbucket. SCM detection is an extension point, so they can come later.
