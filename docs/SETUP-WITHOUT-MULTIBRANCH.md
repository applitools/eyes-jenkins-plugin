# Setup without multibranch

**The recommended setup is a Multibranch Pipeline with a GitHub App** ([setup guide](SETUP.md)). Use this guide when that isn't an option: your Jenkins must use plain Pipeline or Freestyle jobs, can't install the GitHub plugins, or can't receive webhooks from GitHub.

You get the same gate: **Visual tests**, **Design review** (label, Eyes-team designers) and **Baseline merge** on PRs. It needs only the Applitools plugin and a GitHub token.

## What's different

| | Recommended (multibranch + GitHub App) | Without multibranch |
| --- | --- | --- |
| Jenkins plugins | Applitools, GitHub Branch Source, GitHub Checks | **Applitools only** (plus whatever triggers your job) |
| GitHub access | A GitHub App | **A token** (Secret text credential) |
| Results on the PR | Checks (with a details page) | **Commit statuses** (same names and Eyes links) |
| PR detection | Automatic | Automatic with GitHub Pull Request Builder; otherwise **passed on the step** |
| Label added after a build | Applied within seconds (webhook) | Applied **within 1–30 minutes** (polled; sooner for recent PRs) |
| Baseline merge when the PR merges | Within seconds (webhook) | Within 1–30 minutes (polled) |
| Webhooks to Jenkins | Required | **Not required** for the gate (your trigger may need its own) |

If the GitHub plugin happens to be installed and receives webhooks, label changes and merges apply instantly here too.

## 1. Install the plugin

**Manage Jenkins → Plugins**: install the Applitools plugin (until it's published: **Advanced settings → Deploy Plugin** with the `.hpi`). Nothing else is required.

## 2. Create a GitHub token

Create a token that can post statuses and read pull requests on the repository:

- **Fine-grained token** (recommended): select the repository. Permissions: **Commit statuses: read and write**, **Pull requests: read**, **Contents: read**, **Metadata: read**.
- **Classic token:** scope `repo` (or `repo:status` plus `public_repo` for a public repository).

For **GitHub Enterprise Server**, create it there and note your API URL (usually `https://github.example.com/api/v3`).

## 3. Add credentials

**Manage Jenkins → Credentials → System → Global credentials → Add Credentials:**

| Kind | ID | Value |
| --- | --- | --- |
| Secret text | `github-token` | The GitHub token |
| Secret text | `applitools-api-key` | Your Eyes **run** key |
| Secret text | `applitools-read-api-key` | Your Eyes **read** key |
| Secret text | `applitools-merge-api-key` | Your Eyes **merge** key (team + user) |

Then set **Designers** and the other options under **Manage Jenkins → System → Applitools Eyes**, as in step 4 of the [setup guide](SETUP.md#4-configure-jenkins).

## 4. Prepare your tests

Same as [step 6 of the setup guide](SETUP.md#6-prepare-your-tests): Storybook and Cypress need nothing; Playwright needs one `failTestsOnDiff` line.

## 5. Wire the step to your PR builds

Add `githubCredentialsId` to the `applitools` step. How the step learns which PR it's testing depends on how your job is triggered.

### Triggered by GitHub Pull Request Builder (ghprb)

Nothing more to pass: the plugin reads ghprb's `ghprbPullId`, `ghprbActualCommit` (the PR's head commit), `ghprbGhRepository`, `ghprbSourceBranch`, `ghprbTargetBranch` and `ghprbPullTitle`.

```groovy
node('playwright') {
  checkout scm
  sh 'npm ci'
  applitools(githubCredentialsId: 'github-token') {
    sh 'npx playwright test'
  }
}
```

### Triggered by Generic Webhook Trigger

Map the PR fields from GitHub's payload to variables, then pass them to the step:

```groovy
pipeline {
  agent { label 'playwright' }
  triggers {
    GenericTrigger(
      token: 'applitools-visual-review',
      genericVariables: [
        [key: 'PR_ACTION', value: '$.action'],
        [key: 'PR_NUMBER', value: '$.pull_request.number'],
        [key: 'PR_SHA',    value: '$.pull_request.head.sha'],
        [key: 'PR_BRANCH', value: '$.pull_request.head.ref'],
        [key: 'PR_TARGET', value: '$.pull_request.base.ref'],
        [key: 'PR_REPO',   value: '$.repository.full_name'],
      ],
      // Build when a PR opens or gets new commits; the plugin polls for labels and merges itself.
      regexpFilterText: '$PR_ACTION',
      regexpFilterExpression: '^(opened|synchronize|reopened)$'
    )
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scmGit(
          branches: [[name: env.PR_SHA]],
          userRemoteConfigs: [[url: "https://github.com/${env.PR_REPO}.git",
                               refspec: '+refs/pull/*/head:refs/remotes/origin/pr/*']])
      }
    }
    stage('Visual tests') {
      steps {
        sh 'npm ci'
        applitools(repo: env.PR_REPO, pullRequest: env.PR_NUMBER, commit: env.PR_SHA,
                   branch: env.PR_BRANCH, targetBranch: env.PR_TARGET,
                   githubCredentialsId: 'github-token') {
          sh 'npx playwright test'
        }
      }
    }
  }
}
```

In the repository, add a webhook: **Settings → Webhooks → Add webhook**, payload URL `https://<your-jenkins>/generic-webhook-trigger/invoke?token=applitools-visual-review`, content type `application/json`, event **Pull requests**.

### Triggered any other way

Pass the same five values, from parameters or wherever your job gets them: `repo` (`owner/repo`), `pullRequest` (number), `commit` (the PR's **head** commit, not a merge commit), `branch` and `targetBranch`. All accept `${VAR}`.

For **GitHub Enterprise Server**, also pass `githubApiUrl: 'https://github.example.com/api/v3'`.

## 6. Require the results before merging

Open a PR so GitHub has seen the statuses, then in **Settings → Branches** (or **Rules → Rulesets**) require:

- `Applitools / Visual tests`
- `Applitools / Design review`

## 7. Check your setup

**Manage Jenkins → Applitools setup**: the **GitHub with a token** row shows whether GitHub accepted the last call, and how many builds report this way.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Log: **"githubCredentialsId is set, but the repository or commit is unknown"** | Nothing told the step which PR it's testing. Pass `repo:` and `commit:` (and `pullRequest:` for PRs). |
| Setup page: **"GitHub with a token … HTTP 401 / 403 / 404"** | The token is wrong, expired, or can't access the repository. Check its permissions (step 2). |
| Statuses land on the wrong commit | `commit:` must be the PR's **head** commit (`pull_request.head.sha`), not a merge commit. |
| A label change takes a while | Expected without webhooks: PRs are polled every minute in their first hour, every 5 minutes for the rest of the day, then every 30 minutes. |
