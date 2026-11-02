# Setup without multibranch

**The recommended setup is a Multibranch Pipeline with a GitHub App** ([setup guide](SETUP.md)). Use this guide when that isn't an option: your Jenkins must use plain Pipeline or Freestyle jobs, can't install the GitHub plugins, or can't receive webhooks from GitHub.

You get the same gate: **Visual tests**, **Design review** (label, Eyes-team designers) and **Baseline merge** on PRs. It needs only the Applitools plugin and a GitHub token.

**Before you start:** the Applitools keys (run, read and merge) and an agent that can run your tests, as in [Before you start](SETUP.md#before-you-start) of the setup guide.

**Prefer configuration as code?** Steps 1, 3 and the job in step 5 fit in one `jenkins.yaml`: see [Configuration as Code](CONFIGURATION-AS-CODE.md#token-plain-pipeline-job).

## What's different

| | Recommended (multibranch + GitHub App) | Without multibranch |
| --- | --- | --- |
| Jenkins plugins | Applitools, GitHub Branch Source, GitHub Checks | **Applitools only** (plus whatever triggers your job) |
| GitHub access | A GitHub App | **A token** (Secret text credential) |
| Results on the PR | Checks (with a details page) | **Commit statuses** (same names and Eyes links) |
| PR detection | Automatic | Automatic with GitHub Pull Request Builder; otherwise **passed on the step** |
| Label added after a build | Applied within seconds (webhook) | Applied **within 1–30 minutes** by default (polled; sooner for recent PRs) |
| Baseline merge when the PR merges | Within seconds (webhook) | Within 1–30 minutes by default (polled) |
| Webhooks to Jenkins | Required | **Not required** for the gate (your trigger may need its own) |

If the GitHub plugin happens to be installed and receives webhooks, label changes and merges apply instantly here too.

## 1. Install the plugin

**Manage Jenkins → Plugins**: install the Applitools plugin (until it's published: **Advanced settings → Deploy Plugin** with the `.hpi`). Nothing else is required.

## 2. Create a GitHub token

One token does everything: Jenkins checks out the repository with it, and the plugin posts statuses and reads the PR with it.

- **Fine-grained token** (recommended). Select the repository, then set **Repository permissions**:

  | Permission | Access | Used for |
  | --- | --- | --- |
  | **Contents** | Read-only | Checking out the code (private repositories) |
  | **Commit statuses** | Read and write | Posting Visual tests, Design review and Baseline merge |
  | **Pull requests** | Read-only | Reading labels, and whether the PR merged |
  | Metadata | Read-only | Required by GitHub; added automatically |

  **Public repository?** You can drop Contents: Jenkins can check out without credentials.
- **Classic token:** scope `repo`. That includes private-repository access.

In an organization that requires approval for fine-grained tokens, an owner approves it under **Settings → Personal access tokens → Pending requests**. If the organization enforces SAML single sign-on, a classic token also needs **Configure SSO → Authorize**.

For **GitHub Enterprise Server**, create it there and note your API URL (usually `https://github.example.com/api/v3`).

## 3. Add credentials

**Manage Jenkins → Credentials → System → Global credentials → Add Credentials:**

| Kind | ID | Value |
| --- | --- | --- |
| **Username with password** | `github-token` | Username: your GitHub username. Password: the token. |
| Secret text | `applitools-api-key` | Your Eyes **run** key |
| Secret text | `applitools-read-api-key` | Your Eyes **read** key |
| Secret text | `applitools-merge-api-key` | Your Eyes **merge** key (team + user) |

A **Username with password** credential works for both the checkout (the git plugin needs that kind) and the plugin, which reads the token from the password. For a public repository, a **Secret text** credential with just the token is enough.

Then set **Designers** and the other options under **Manage Jenkins → System → Applitools Eyes**, as in step 4 of the [setup guide](SETUP.md#4-configure-jenkins).

## 4. Prepare your tests

Same as [step 6 of the setup guide](SETUP.md#6-prepare-your-tests): Storybook and Cypress need nothing; Playwright needs one `failTestsOnDiff` line.

## 5. Create the job and wire the step to your PR builds

**New Item → Pipeline**, then under **Pipeline**:

- **Definition:** *Pipeline script from SCM*
- **SCM:** Git, with your repository URL and, for a private repository, the `github-token` credential
- **Branch Specifier:** `*/main` (where the Jenkinsfile lives; the Jenkinsfile itself checks out the PR)
- **Script Path:** `Jenkinsfile`, or another name if the repository already has one for other jobs

Pasting the script into the job (*Pipeline script*) works too.

In the Jenkinsfile, add `githubCredentialsId` to the `applitools` step. How the step learns which PR it's testing depends on how your job is triggered: pick one of the three below.

**After saving, build the job once by hand** (**Build Now**). Jenkins only learns the triggers and parameters a Jenkinsfile declares by running it. That first build may fail for lack of PR values; that's expected.

### Triggered by GitHub Pull Request Builder (ghprb)

Nothing more to pass: the plugin reads ghprb's `ghprbPullId`, `ghprbActualCommit` (the PR's head commit), `ghprbGhRepository`, `ghprbSourceBranch`, `ghprbTargetBranch` and `ghprbPullTitle`. For a private repository, select `github-token` as the credentials in the job's Git configuration (ghprb's own GitHub connection is configured separately, in its plugin settings).

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

Install the **Generic Webhook Trigger** plugin. Map the PR fields from GitHub's payload to variables, then pass them to the step:

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
        [key: 'PR_HEAD_REPO', value: '$.pull_request.head.repo.full_name'],
      ],
      // Build when a PR opens or gets new commits; the plugin polls for labels and merges itself.
      // Only PRs from this repository's own branches: a fork's code would get your Eyes key.
      regexpFilterText: '$PR_HEAD_REPO $PR_REPO $PR_ACTION',
      regexpFilterExpression: '^(\\S+) \\1 (opened|synchronize|reopened)$'
    )
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scmGit(
          branches: [[name: env.PR_SHA]],
          userRemoteConfigs: [[url: "https://github.com/${env.PR_REPO}.git",
                               credentialsId: 'github-token',   // private repo: the same token checks out
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

The checkout fetches `refs/pull/*/head`, which exists for every PR, so the PR's head commit can be checked out even when it comes from a branch Jenkins doesn't otherwise fetch.

In the repository, add a webhook: **Settings → Webhooks → Add webhook**, payload URL `https://<your-jenkins>/generic-webhook-trigger/invoke?token=applitools-visual-review`, content type `application/json`, event **Pull requests**. If GitHub can't reach your Jenkins, relay it as in [Local Jenkins: webhook relay](SETUP.md#local-jenkins-webhook-relay), with that payload URL as the relay's target.

### Triggered any other way

Pass the same five values, from parameters or wherever your job gets them: `repo` (`owner/repo`), `pullRequest` (number), `commit` (the PR's **head** commit, not a merge commit), `branch` and `targetBranch`. All accept `${VAR}`.

For example, as build parameters, which also makes for an easy first test: **Build with Parameters**, fill in an open PR's values, and its statuses appear on the PR.

```groovy
pipeline {
  agent { label 'playwright' }
  parameters {
    string(name: 'PR_REPO',   description: 'owner/repo')
    string(name: 'PR_NUMBER', description: 'Pull request number')
    string(name: 'PR_SHA',    description: "The PR's head commit")
    string(name: 'PR_BRANCH', description: "The PR's branch")
    string(name: 'PR_TARGET', defaultValue: 'main', description: 'The branch it merges into')
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scmGit(
          branches: [[name: params.PR_SHA]],
          userRemoteConfigs: [[url: "https://github.com/${params.PR_REPO}.git",
                               credentialsId: 'github-token',
                               refspec: '+refs/pull/*/head:refs/remotes/origin/pr/*']])
      }
    }
    stage('Visual tests') {
      steps {
        sh 'npm ci'
        applitools(repo: params.PR_REPO, pullRequest: params.PR_NUMBER, commit: params.PR_SHA,
                   branch: params.PR_BRANCH, targetBranch: params.PR_TARGET,
                   githubCredentialsId: 'github-token') {
          sh 'npx playwright test'
        }
      }
    }
  }
}
```

For **GitHub Enterprise Server**, also pass `githubApiUrl: 'https://github.example.com/api/v3'`.

## 6. Require the results before merging

Open a PR so GitHub has seen the statuses, then in **Settings → Branches** (or **Rules → Rulesets**) require:

- `Applitools / Visual tests`
- `Applitools / Design review`

## 7. Check your setup

**Manage Jenkins → Applitools setup → Run checks**: the **GitHub with a token** row shows whether GitHub accepted the last call, and how many builds report this way. The keys rows should be ✅ before your first PR build.

## 8. Try it

Same as [Try it](SETUP.md#9-try-it) in the setup guide, with two differences: results are **commit statuses** (waiting for review shows as yellow *pending*), and a label added after the build, or the PR's merge, is picked up within 1–30 minutes rather than seconds.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Log: **"githubCredentialsId is set, but the repository or commit is unknown"** | Nothing told the step which PR it's testing. Pass `repo:` and `commit:` (and `pullRequest:` for PRs). |
| Setup page: **"GitHub with a token … HTTP 401 / 403 / 404"** | The token is wrong, expired, or can't access the repository. Check its permissions (step 2). |
| Checkout fails: **"Authentication failed"** or **"repository not found"** (private repo) | The checkout needs `credentialsId: 'github-token'` on a **Username with password** credential, and the token needs **Contents: read-only** on the repository. |
| Statuses land on the wrong commit | `commit:` must be the PR's **head** commit (`pull_request.head.sha`), not a merge commit. |
| A label change takes a while | Expected without webhooks: By default, PRs are polled every minute in their first hour, every 5 minutes for the rest of the day, then every 30 minutes. Change it under **Review polling** in Manage Jenkins → System ([Polling schedule](OPERATIONS.md#polling-schedule)). |
