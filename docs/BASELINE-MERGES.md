# Baseline merges and conflicts

When a pull request merges, the plugin merges its Eyes baselines into the target branch. Usually that just works: the changes reviewers accepted on the PR become `main`'s baselines, and the next build of `main` doesn't flag them again.

This page explains what happens when the merge **can't** run, most often because of baseline conflicts. In short: the plugin makes it impossible to miss, and it keeps reminding you until it's fixed.

## The normal case

1. The PR merges. GitHub tells Jenkins (a webhook, or polling for jobs that use a token).
2. The plugin asks Eyes to merge the PR's Eyes branch (`owner/repo/feature`) into the target's (`owner/repo/main`).
3. **Applitools / Baseline merge** ✅ *Baselines merged into main* appears on the PR's last tested commit.
4. The build of `main` that the merge starts compares against the merged baselines. Its console says so: `[Applitools] Baseline merge from PR #13: Baselines merged into main.`

Nothing else happens, and nothing else is posted.

## When the baselines can't be merged

The merge fails when:

- **Baseline conflicts:** the same checkpoints also changed on the target branch since the PR branched off. This is the common case.
- **Eyes refused the key:** only Eyes **merge** keys can merge baselines.
- **No merge key** was available.
- **Eyes took too long** (more than 10 minutes, by default).
- **The PR's build was deleted** before the merge could run (multibranch deletes closed PRs' jobs).

Here's what you'll see:

| Where | What | Who notices |
| --- | --- | --- |
| **The merged PR: a comment** | ⚠️ *Eyes baselines from this PR weren't merged into `main`*, with the reason and the fix. | Everyone on the PR (author, reviewers, subscribers) gets a GitHub notification. |
| **The merged PR: a label** | `eyes-baselines-not-merged` | Anyone browsing PRs. Search for all of them with `is:pr label:eyes-baselines-not-merged`. |
| **The merged PR: a check** | **Applitools / Baseline merge** ❌ with the reason, on its last tested commit | Anyone opening the PR. |
| **Every new commit on `main`: a check** | **Applitools / Baseline merge** ❌ *Baselines of a merged PR aren't merged*, listing which PRs and why | Everyone: it's the red ❌ next to the latest commit on the repository's home page. |
| **Every build of `main`** | Marked **unstable** (yellow), with a `WARNING:` line in its console | Whoever watches the Jenkins job. |
| **Manage Jenkins → Applitools setup** | Listed under **Unmerged baselines**, with the fix and whether the PR comment could be posted | Jenkins admins. |

These stay in place until the baselines are merged, however many builds and pushes later.

### How each build of `main` retries

Before its tests run, every build of a branch (for example `main`) retries the merges into that branch that haven't happened yet. It uses its own Eyes merge key, so the retry works even after the PR's build is gone.

- **Still failing:** the build gets a failing **Applitools / Baseline merge** check and is marked unstable. Its visual tests still run, but they may flag the changes the PR accepted, because those aren't in `main`'s baselines yet.
- **Can't retry** because this build has no usable Eyes merge key: the check still fails, says the merge wasn't retried and why, and keeps the PR's real reason (for example, the conflicts) rather than replacing it with "key refused".
- **Succeeds:** the merge happens. The build's check turns ✅ *Retried baseline merge succeeded*, the PR's comment changes to ✅ *Eyes baselines from this PR are merged into `main`*, and the label comes off. Its tests then compare against the merged baselines.

### Fixing it

| Reason | Fix |
| --- | --- |
| **N baseline conflicts** | In Eyes, open **Compare & merge branches**, compare `owner/repo/<PR branch>` → `owner/repo/<target branch>`, resolve the conflicts, then merge. |
| **Eyes refused the baseline merge** | Add an Eyes **merge** key as a Secret text credential `applitools-merge-api-key` (Eyes → Admin → API Keys, with the **merge** option). |
| **Baseline merge skipped** (no key) | The same: add a merge key. |
| **Baseline merge timed out** | Check the branches in Eyes. A later build retries the merge. |
| **Baseline merge skipped: build gone** | Nothing: the next build of the target branch merges it with its own credentials. |

Then run the target branch's build again, or wait for the next push. When its retry succeeds, everything clears on its own.

If you merged the baselines in Eyes yourself, the next build's retry finds nothing left to merge and clears everything the same way. The ✅ comment then reads *Nothing was left to merge from `src` into `tgt`*.

The [GitHub Action](https://github.com/applitools/eyes-github-action) works the same way, with the same comment, label and check.

## Settings

Both settings are under **Manage Jenkins → System → Applitools Eyes → Baselines**, and both are on by default.

| Setting | Key | What it does |
| --- | --- | --- |
| When a merged pull request's baselines can't be merged, comment on it and label it | `commentOnUnmergedBaselines` | The PR comment and the `eyes-baselines-not-merged` label. |
| Mark target branch builds unstable until those baselines are merged | `unstableWhileUnmerged` | The yellow build. Turned off, builds stay green, but the failing check on `main` still appears. |

Turning off `mergeBaselines` turns off baseline merging altogether, and with it everything on this page.

## GitHub permissions

Commenting and labeling need **Pull requests: Read and write**. That's one step up from the read-only access needed otherwise.

- **GitHub App:** App settings → **Permissions & events → Repository permissions → Pull requests: Read and write**, then accept the new permission in the installation (GitHub asks the installation owner by email or on the installation page).
- **Fine-grained token:** **Pull requests: Read and write**.
- **Classic token:** `repo` covers it.

Without it, the comment and label can't be posted. The plugin logs a warning, and **Manage Jenkins → Applitools setup → Unmerged baselines** shows *Not posted* with the reason. The failing check on `main` and the unstable builds still work: they need only the permissions you already have.

## For the curious

- The list of unmerged PRs is kept in `$JENKINS_HOME/io.jenkins.plugins.applitools.review.UnmergedBaselines.xml`. An entry leaves it only when its baselines merge. Unlike the merge history, it's never trimmed.
- The plugin keeps a single comment per PR and edits it instead of adding new ones. It finds its own comment by a hidden marker, `<!-- applitools-baseline-merge -->`. It's re-posted only when the reason changes, so retries don't spam the PR.
- Each retry waits at most 2 minutes for Eyes (system property `io.jenkins.plugins.applitools.BaselineMergeAlerts.retryTimeoutSeconds`), since the build's tests are waiting on it.
- Retries use the target branch build's own Eyes server and merge key (configured merge key → `applitools-merge-api-key` → the build's run key). They never use settings from the PR's Jenkinsfile.
- A redelivered `closed` webhook (GitHub App → Advanced → Recent Deliveries) still retries a failed merge too, as long as the PR's build exists.
