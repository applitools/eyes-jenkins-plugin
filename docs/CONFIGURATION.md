# Configuration

Every setting the plugin has and what it does. The plugin is configured in two places:

- **Global settings**, under **Manage Jenkins → System → Applitools Eyes**. They're the defaults for every job, and some (security, polling) apply only here. Each has a [Configuration as Code](CONFIGURATION-AS-CODE.md) key under `unclassified: applitools:`.
- **Step options** on the `applitools { }` step in a Jenkinsfile, or in **Build Environment → Applitools Eyes** for Freestyle jobs. They override the global settings for that job.

Nothing is required beyond the run key: with an `applitools-api-key` credential in place, the defaults work. For a first setup, follow the [setup guide](SETUP.md) instead; this page is the reference.

## Global settings

The **Key** column is the Configuration as Code key. Settings in **Review polling** and **Advanced** are under the collapsed sections of the same names.

### Eyes server and API keys

| Setting | Key | Default | What it does |
| --- | --- | --- | --- |
| Server URL | `serverUrl` | `https://eyes.applitools.com` | Eyes server, for dedicated clouds and on-premises. Must be `https://`, or `http://` on the same machine (see `allowHttp`). Link-local and cloud metadata addresses are refused. |
| Run key | `credentialsId` | `applitools-api-key` | Secret text credential with an Eyes **run** key. Passed to the tests to upload results. |
| Read key | `readCredentialsId` | `applitools-read-api-key` if that credential exists, otherwise the run key | Secret text credential with a key that can **read** results. Run keys can upload but get HTTP 403 reading results back, so without a read key the plugin can't show results or enforce the gates. |
| Merge key | `mergeCredentialsId` | `applitools-merge-api-key` if that credential exists, otherwise the run key | Secret text credential with a key allowed to **merge baselines** when a PR merges. |

### Pull request results

| Setting | Key | Default | What it does |
| --- | --- | --- | --- |
| Report to GitHub as | `githubReporting` | `AUTO` | `AUTO`: GitHub checks for jobs that connect with a GitHub App, commit statuses for jobs that use a token (GitHub only accepts checks from Apps). `CHECKS` or `STATUSES` force one. Both carry the same names, results and links, and both can be required in branch protection. |
| Post the "Applitools / Design review" result on every pull request | `designReviewCheck` | `true` | Posts Design review on every build, as *skipped* when design review isn't requested, so it can be a required check. When `false`, design review is off: labels are ignored and the check isn't posted, unless a Jenkinsfile sets `designReview: true`. |

### Design review

| Setting | Key | Default | What it does |
| --- | --- | --- | --- |
| Designers | `designers` | empty: anyone can approve | Who can approve a design review: `team:<Eyes team>` (everyone in that Eyes team; names may contain spaces) and/or Eyes login emails, separated by commas or new lines. When set, changes accepted in Eyes by anyone else don't satisfy Design review. If a team can't be read from Eyes, nobody from it can approve. |
| Label | `designReviewLabel` | `design-review` | Adding this label to a pull request requires a designer's approval. Removing it drops the requirement. |
| Require design review on every pull request | `designReviewByDefault` | `false` | Every PR needs a designer's approval, labeled or not. |
| Jenkinsfiles can't loosen design review | `lockDesignReview` | `false` | Ignores Jenkinsfile settings that would weaken the gate: `designReview: false`, `designers`, `designReviewLabel` and `checks: false`. A Jenkinsfile can still require design review. Use it when people who can edit Jenkinsfiles shouldn't be able to skip design approval. |

### Baselines

| Setting | Key | Default | What it does |
| --- | --- | --- | --- |
| When a pull request merges, merge its Eyes baselines into the target branch | `mergeBaselines` | `true` | When a PR merges, the changes accepted on its Eyes branch become the target branch's baselines, so the next build of `main` compares against them. Posted to the PR as **Applitools / Baseline merge**. A merge that fails is retried by every build of the target branch until it succeeds; see [Baseline merges and conflicts](BASELINE-MERGES.md). |
| When a merged pull request's baselines can't be merged, comment on it and label it | `commentOnUnmergedBaselines` | `true` | Comments on the PR (notifying its author and reviewers) with the reason and the fix, and adds the `eyes-baselines-not-merged` label. Both are updated once the baselines merge. Needs GitHub **Pull requests: Read and write**. |
| Mark target branch builds unstable until those baselines are merged | `unstableWhileUnmerged` | `true` | While a merged PR's baselines aren't merged, builds of its target branch are unstable (yellow). Their failing **Applitools / Baseline merge** check appears either way. |

### Review polling

After a build, the plugin keeps checking Eyes for reviewers' decisions and updates the PR, so accepting changes in Eyes turns the results green without re-running the build. It checks less often as a review ages. PRs reported with a token are checked on GitHub, for label changes and merges, on the same schedule.

| Setting | Key | Default | What it does |
| --- | --- | --- | --- |
| Check every (seconds) | `reviewPollSeconds` | `60` | How often to check a new review. At least 15. |
| Slow down after (minutes) | `reviewSlowAfterMinutes` | `60` | When a review moves to the next interval. |
| Then check every (minutes) | `reviewSlowPollMinutes` | `5` | Never shorter than the first interval. |
| Slow down again after (hours) | `reviewSlowestAfterHours` | `24` | When a review moves to the slowest interval. |
| Then check every (minutes) | `reviewSlowestPollMinutes` | `30` | Never shorter than the previous interval. |
| Recheck fully reviewed pull requests every (minutes) | `settledRecheckMinutes` | `30` | A PR whose changes are all accepted or rejected is still re-read now and then, in case a reviewer changes their mind in Eyes. |
| Stop following a review after (days) | `followReviewsDays` | `14` | After this, a review is dropped and its PR results stay as they are. 1 to 365. |

After an error, the interval doubles each time, up to an hour (or the slowest interval, if that's longer). Shorter intervals update PRs sooner but make more Eyes API calls; see [Resource usage and limits](OPERATIONS.md#polling-schedule) for the cost.

### Advanced

| Setting | Key | Default | What it does |
| --- | --- | --- | --- |
| Keep updating results for branch builds | `followBranchBuilds` | `false` | Also follow branch builds (e.g. `main`) after they finish. Off by default: nobody reviews branch builds, so their results never settle. PR builds are always followed. |
| Don't name reviewers in pull request results | `hideReviewers` | `false` | Results say "Approved by a designer" instead of the reviewer's Eyes login email. Useful for public repositories. |
| Run visual tests on pull requests from forks | `allowForkPullRequests` | `false` | Off by default, because a fork's test code would receive the Eyes run key. When on, fork PRs run on their own Eyes branch (`owner/repo/pr-N`). |
| Allow http:// Eyes and GitHub URLs on other machines | `allowHttp` | `false` | Plain `http://` is otherwise allowed only for the same machine (`localhost`), since keys would travel unencrypted. |
| Older on-premises Eyes: retry closing batches with the run key in the URL | `apiKeyInQuery` | `false` | For older on-premises Eyes servers that reject the key in a header. Off by default: URL parameters end up in proxy and server logs. |

## Step options

All are optional. In a Jenkinsfile:

```groovy
applitools(onChanges: 'UNSTABLE', expectApps: ['Web', 'Storybook']) {
  sh 'npx playwright test'
}
```

Options marked *global setting* use the setting above when left out. Text options accept `${VAR}` from the build's environment.

### Keys and server

| Option | Default | What it does |
| --- | --- | --- |
| `credentialsId` | global setting | Secret text credential with the Eyes run key. In Pipeline, the key is masked in the log, encrypted in the build's saved state, and flagged if a script puts it into a Groovy string (`"${env.APPLITOOLS_API_KEY}"`). Pass it to tools through the environment instead. |
| `readCredentialsId` | global setting | Key used to read results |
| `serverUrl` | global setting | Eyes server for this job |

### Results and the build

| Option | Default | What it does |
| --- | --- | --- |
| `onChanges` | `'SUCCESS'` | Build result when there are changes waiting for review: `'SUCCESS'` (green: the PR results are the gate), `'UNSTABLE'` or `'FAILURE'`. Rejected or aborted tests and missing results always fail the build. |
| `waitForResults` | `true` | Wait for Eyes results before the block ends. With `false`, the build doesn't read results, and Visual tests says it didn't wait for Eyes. |
| `resultsTimeout` | `300` | Seconds to wait for tests still rendering in Eyes |
| `judgeDiffs` | `true` | Tells SDKs that support it (Storybook, Cypress) not to fail the test command on diffs, while errors still fail it, so the plugin decides. `false` keeps the SDKs' own behavior. Playwright needs one config line instead (see the [setup guide](SETUP.md)). |
| `expectTests` | none | The batch must contain at least this many Eyes tests, or Visual tests and the build fail. Catches a test run that silently ran nothing. |
| `expectApps` | none | Eyes app names that must each report at least one test, e.g. `['Web', 'Storybook']` |
| `checks` | `true` | Post the PR results (checks or commit statuses). Ignored when design review is locked. |
| `mergeBaselines` | global setting | `true` or `false`: merge this job's Eyes baselines into the target branch's when its PR merges |

### Design review

| Option | Default | What it does |
| --- | --- | --- |
| `designReview` | from the PR label | `true` requires a designer's approval, `false` skips it, whatever the label says. `false` is ignored when design review is locked. |
| `designReviewLabel` | global setting | PR label that requests design review |
| `designers` | global setting | Who may approve, as in the global setting, comma-separated. Use double quotes if a name has an apostrophe: `designers: "team:Tim's Eyes Org"`. |

Whether a PR needs design review is decided by the first of these that's set: the `designReview` option → the `APPLITOOLS_DESIGN_REVIEW=true|false` environment variable → the PR label → **Require design review on every pull request**.

### Batch and branch

By default these come from the PR: the batch is the PR's head commit, and branches follow Eyes' `owner/repo/branch` convention, so baselines line up with the Eyes GitHub integration if you also use it.

| Option | Default | What it does |
| --- | --- | --- |
| `batchName` | `owner/repo PR #7: <title>`, or `owner/repo <branch>` for branch builds | Eyes batch name |
| `batchId` | the commit under test | Eyes batch ID. Every SDK in the block reports to this batch. |
| `branchName` | `owner/repo/branch` | Eyes branch the tests run on |
| `parentBranchName` | `owner/repo/<target branch>` | Eyes branch whose baselines a new branch starts from |
| `closeBatch` | `true` | Close the batch when the block ends |

### Pull request detection (jobs that aren't multibranch)

Multibranch jobs detect all of this on their own. For other jobs, see [Setup without multibranch](SETUP-WITHOUT-MULTIBRANCH.md).

| Option | Default | What it does |
| --- | --- | --- |
| `githubCredentialsId` | none | Token credential (Secret text, or username and password with the token as the password) to post commit statuses and read the PR. Needs no GitHub plugins. |
| `githubApiUrl` | `https://api.github.com` | For GitHub Enterprise Server, e.g. `https://github.example.com/api/v3` |
| `repo` | detected | `owner/repo` |
| `pullRequest` | detected | PR number |
| `commit` | detected | The commit being tested; for a PR, its head commit, not a merge commit |
| `branch` | detected | Branch under test; for a PR, its source branch |
| `targetBranch` | detected | The PR's target branch |

## Environment variables

**Set by the plugin** inside the block, for the Eyes SDKs and your test code. Variables you've set yourself are left alone.

| Variable | Value |
| --- | --- |
| `APPLITOOLS_API_KEY` | The run key (masked in logs) |
| `APPLITOOLS_SERVER_URL` | The Eyes server |
| `APPLITOOLS_BATCH_ID`, `APPLITOOLS_BATCH_NAME`, `APPLITOOLS_BATCH_SEQUENCE` | The batch |
| `APPLITOOLS_BRANCH_NAME`, `APPLITOOLS_PARENT_BRANCH_NAME` | The Eyes branches |
| `APPLITOOLS_DONT_CLOSE_BATCHES` | `true`: the plugin closes the batch itself |
| `APPLITOOLS_JENKINS_MANAGED` | `true`, so test configs can detect the plugin (e.g. Playwright's `failTestsOnDiff`) |
| `APPLITOOLS_EXITCODE`, `APPLITOOLS_FAIL_CYPRESS_ON_DIFF` | `nodiffs` and `false`, unless `judgeDiffs: false` |

**Read by the plugin:** `APPLITOOLS_DESIGN_REVIEW=true|false` requires or skips design review, if the `designReview` option isn't set.

## `applitoolsResults()`

Returns the build's Eyes results for your own logic or notifications:

```groovy
def eyes = applitoolsResults()   // status: PASSED, CHANGES, FAILED or NO_RESULTS
if (eyes?.status == 'CHANGES') {
  echo "Visual changes to review: ${eyes.url}"
}
```

## Limits for large installations

Caps such as the number of builds followed at once, the time budget per polling pass and the baseline merge timeout are Java system properties on the controller. Their defaults suit most installations; see [Tuning](OPERATIONS.md#tuning).
