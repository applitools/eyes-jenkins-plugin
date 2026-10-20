# Plan: PR-driven visual review (Chromatic-style flow) for Jenkins + Applitools Eyes

## Goal

Match this flow end to end, with Jenkins as the CI:

1. Developer opens a PR on GitHub.
2. Jenkins runs the visual tests against Applitools Eyes automatically.
3. The PR gets an **Applitools Eyes** status with a link to the diffs.
4. A designer opens that link, reviews in the Eyes dashboard, and accepts or rejects the changes. They never need to see GitHub.
5. Unresolved or rejected diffs **block the merge** until the designer approves.

## Key design decision: who owns the GitHub status?

Step 5 is the hard part. The designer approves *after* Jenkins has finished, so whatever posts the PR status has to learn about the approval and update it. Jenkins can't see that without polling or re-running.

**Recommendation: let Eyes own the PR status, and have Jenkins feed it correct data.**

Applitools Eyes already has a GitHub integration (the Applitools GitHub app plus SCM integration in the Eyes admin panel). When a batch's ID is the PR's **head commit SHA** and branch names follow the integration's format, Eyes:
- posts a commit status on the PR, linking to the batch;
- updates it to passed or failed as reviewers resolve diffs in the dashboard, with no CI re-run;
- can merge the branch baseline into the parent baseline when the PR merges.

That covers steps 3–5 for us. **This plugin's job is to make Jenkins produce Eyes batches that the integration recognizes, with no hand-written Jenkinsfile glue.** Today that glue is where setups break.

| Option | Pros | Cons |
| --- | --- | --- |
| **A. Eyes posts status (recommended)** | Status updates live when the designer approves; one source of truth; baseline merge for free | Needs the Applitools GitHub app installed and the repo linked in Eyes (an org-admin step) |
| B. Jenkins posts status (Checks API) | Everything is in Jenkins | Doesn't know when the designer approves, so it needs polling or a re-run, and the merge gate goes stale |
| C. Both | Jenkins check shows "tests ran or crashed"; Eyes status shows "visually approved" | Two checks on the PR; we have to name them clearly |

Phase 3 can add C as an option: a Jenkins-side check for *execution* failures (tests crashed, no results uploaded), which Eyes can't report.

## Gotchas that break this flow in a hand-rolled Jenkinsfile

These are the reasons setup is hard today, and what the plugin has to handle for users:

- **Wrong SHA on PR builds.** In a Multibranch PR build, GitHub Branch Source checks out the PR *merged with the target branch* by default. That makes `GIT_COMMIT` a synthetic merge commit GitHub has never heard of, so a batch ID based on it puts the Eyes status nowhere. The correct value is the PR head hash (`PullRequestSCMRevision.getPullHash()`).
- **Missing branch / parent-branch names.** Without them, PR baselines don't branch off the target branch and don't merge back on merge.
- **Batch never closed, or closed too early.** Each test runner closes its own batch by default, which splits results. If nothing closes the batch, the PR status never finalizes.
- **API key handled as a raw string** instead of a masked Jenkins credential.

## What the plugin provides

### 1. Run Eyes for a GitHub PR, with zero glue

A Pipeline block step, which also works in Freestyle as a Build Environment wrapper:

```groovy
applitools {
  sh 'npm run test:visual'
}
```

Inside the block, the plugin derives everything from the build's SCM context and exports it for the Eyes SDKs to pick up:

| Env var | PR build | Branch build (e.g. `main`) |
| --- | --- | --- |
| `APPLITOOLS_API_KEY` | from a Jenkins **Secret text credential** (masked in logs) | same |
| `APPLITOOLS_SERVER_URL` | global config (public cloud / dedicated / on-prem) | same |
| `APPLITOOLS_BATCH_ID` | PR **head** SHA | commit SHA |
| `APPLITOOLS_BATCH_NAME` | `<repo> PR #<n>: <title>` | `<repo> <branch>` |
| `APPLITOOLS_BRANCH_NAME` | `<owner>/<repo>/<source-branch>` | `<owner>/<repo>/<branch>` |
| `APPLITOOLS_PARENT_BRANCH_NAME` | `<owner>/<repo>/<target-branch>` | unset |
| `APPLITOOLS_DONT_CLOSE_BATCHES` | `true` (the plugin closes it) | same |

Sources: `SCMRevisionAction` / `PullRequestSCMRevision` and `GitHubSCMSource` (an optional dependency on `github-branch-source`), with `CHANGE_*` env vars as a fallback. Any of these can be overridden explicitly.

When the block exits, whether it passed or failed:
- **Close the batch** (`DELETE /api/sessions/batches/{id}/close/bypointerid`). This tells the SCM integration that the batch is complete, so it finalizes the PR status. It's safe with parallel stages: only the outermost/last block closes the batch, tracked per build.
- **Fetch the batch summary**: passed / unresolved / failed counts and the batch URL.

**Triggering is not ours to build.** Jenkins' GitHub Branch Source (Multibranch Pipeline or Organization Folder) already discovers PRs via webhook and builds them. We document that setup and detect it at runtime.

### 2. Results back to Jenkins (and to GitHub through Eyes)

- **Build page:** an "Applitools Eyes" summary box and sidebar link showing the counts and a **Review in Eyes** button (the designer's link).
- **Build result policy** (`onUnresolved: 'UNSTABLE' | 'FAILURE' | 'SUCCESS'`, default `UNSTABLE`). The *merge gate* is the Eyes GitHub status, not the Jenkins build result. Failing the Jenkins build on diffs would make the Jenkins check red even after the designer approves, which is the stale-gate problem from option B.
- **Pipeline return value:** `def r = applitools { ... }` returns counts and the URL, for custom notifications.
- **Optional (option C):** an `Applitools Eyes / execution` check via `checks-api` / `github-checks`, red only if tests crashed or uploaded nothing.

### 3. Getting the link to the designer

- **Primary:** the PR status's "Details" link opens the Eyes batch. Designers need an Eyes account with a Reviewer role.
- **Not via GitHub:** Eyes' own batch-completion notifications (email/Slack, configured in Eyes admin) plus the batch URL on the Jenkins build page. The plugin asks Eyes to send these notifications when it closes the batch.
- The plugin won't build its own Slack/email integration; `r.batchUrl` plus existing Jenkins notifier steps cover it.

### 4. "Easily link up the PR": setup experience

- **Manage Jenkins → System → Applitools:** server URL, default credential, **Test connection** button (validates the API key against the server).
- **Setup checklist page** (Manage Jenkins → Applitools setup), which detects and reports:
  - credential configured and valid
  - GitHub Branch Source installed, and which jobs are multibranch on GitHub
  - a reminder (we can't check this from Jenkins) to install the Applitools GitHub app, link the repo in Eyes admin, and add the branch protection rule
- **Docs (`docs/github-pr-setup.md`):** a 5-step guide with screenshots:
  1. Install the Applitools GitHub app on the org/repo, and enable SCM integration in Eyes admin.
  2. Add the API key as a Jenkins Secret text credential.
  3. Create a Multibranch Pipeline (GitHub source, PR discovery, webhook).
  4. Wrap the visual tests in `applitools { }` in the Jenkinsfile (sample provided).
  5. GitHub branch protection: require the **Applitools Eyes** status check.
- The Snippet Generator works automatically from `@Symbol` and the config forms.

## Phases

| Phase | Scope | Exit criteria |
| --- | --- | --- |
| **0. Spike** (1–2 days) | Manually wire a sample repo (GitHub + Jenkins multibranch + Eyes SCM integration) using only Jenkinsfile env vars. Answer the open questions below. | A PR shows the Eyes status; designer approval flips it green; merge is blocked until then |
| **1. Core run** | Credentials, global config, `applitools {}` wrapper, SCM detection (PR head SHA, branch names), batch close | Spike repo works with just `applitools { sh ... }`; JenkinsRule tests with a mocked Eyes server |
| **2. Results** | Batch summary fetch, build action/summary, result policy, Pipeline return value | Build page shows counts and the review link |
| **3. GitHub polish** | Setup checklist page, Test connection, docs, optional Checks API execution check | A new user goes from zero to a gated PR by following the docs alone |
| **4. Hardening and release** | Parallel-stage batching, fork PRs, on-prem/dedicated servers, Jenkins plugin hosting (new plugin ID) | Sample repo passes all spike scenarios; released |

## Open questions to settle in the spike

1. **Status context name** that Eyes posts. Branch protection needs the exact string. Is it a commit status or a check run?
2. **Exact branch-name format** the SCM integration expects (`owner/repo/branch`?), and whether `PARENT_BRANCH_NAME` must be set for PR baselines to inherit from the target.
3. **When does the PR status first appear:** on the first test session, or only on batch close? This affects how early the PR shows "pending".
4. **Batch close vs. integration:** is `close/bypointerid` enough, or does the SCM integration need anything else (e.g. `bindpointers`)?
5. **Results API:** the supported endpoint for batch summary counts and the batch URL for a given pointer ID.
6. **Fork PRs:** credentials aren't exposed to untrusted fork builds by default. Do we skip, or run with a restricted key?
7. **Re-runs:** a second build of the same head SHA reuses the batch ID. Does Eyes append to or replace the batch, and does the status reset?
8. **GitHub Enterprise Server:** does the Eyes integration support it, and does that affect our detection?

## Not in scope (for now)

- Our own GitHub App or webhook receiver (Eyes and GitHub Branch Source already cover these).
- A review UI inside Jenkins (the designer reviews in Eyes).
- GitLab / Bitbucket. The SCM detection layer should stay pluggable so these can come later.
