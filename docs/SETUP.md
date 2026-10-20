# Setup guide: visual review on every pull request

When you're done, every pull request gets two checks from Jenkins:

| Check | Passes when |
| --- | --- |
| **Applitools / Visual tests** | Every visual test passed, or its changes were accepted in Eyes |
| **Applitools / Design review** | Not requested (skipped), or a designer approved the changes in Eyes |

Both checks link straight to the results in Eyes. Accepting changes in Eyes turns them green within about a minute, with no re-run. Making them required in branch protection blocks the merge until then.

Setup takes about 30 minutes. Do the steps in order.

## Before you start

- [ ] **Jenkins 2.568 or newer** that GitHub can send webhooks to. If yours is on a laptop or behind a firewall, see [Local Jenkins: webhook relay](#local-jenkins-webhook-relay).
- [ ] **Admin access to the GitHub repo** (to install a GitHub App and set branch protection).
- [ ] **Two Applitools API keys:** a normal **run** key (uploads tests) and a **read** key (lets Jenkins read results). Create the read key in Eyes under **Admin → API Keys**.
- [ ] **A Jenkins agent that can run your tests:** Node.js plus the browsers your tests need (for example, the official Playwright image).

## 1. Install the Jenkins plugins

**Manage Jenkins → Plugins → Available plugins**, then install:

- **GitHub Branch Source** (builds pull requests)
- **GitHub Checks** (posts checks to pull requests)
- **Pipeline** (if not already installed)

Then install the Applitools plugin. Until it's published, upload the `.hpi` file at **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin**.

Restart Jenkins.

## 2. Create a GitHub App

Jenkins talks to GitHub as a GitHub App. Posting checks requires one; a personal token isn't enough.

1. Generate a webhook secret and keep it handy:
   ```bash
   openssl rand -hex 32
   ```
2. Go to **GitHub → Settings → Developer settings → GitHub Apps → New GitHub App** (use the organization's settings for an org repo).
   - **Homepage URL:** your repo's URL (anything valid works).
   - **Callback URL, "Request user authorization", "Post installation":** leave empty or unchecked.
   - **Webhook URL:** `https://<your-jenkins>/github-webhook/` (the trailing slash matters).
   - **Webhook secret:** the secret from step 1.
3. **Repository permissions:**

   | Permission | Access |
   | --- | --- |
   | Administration | Read-only |
   | Checks | Read and write |
   | Commit statuses | Read and write |
   | Contents | Read-only |
   | Metadata | Read-only |
   | Pull requests | Read-only |

4. **Subscribe to events:** Check run, Check suite, Pull request, Push, Repository.
5. **Create the App.** Note the **App ID** (a number at the top of the page). It isn't the Client ID.
6. **Private keys → Generate a private key.** Convert the downloaded file to the format Jenkins expects:
   ```bash
   openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in downloaded-key.pem -out jenkins-github-app.pem
   ```
7. **Install App → Only select repositories →** your repo.

## 3. Add credentials to Jenkins

**Manage Jenkins → Credentials → System → Global credentials → Add Credentials.** IDs must match exactly:

| Kind | ID | Value |
| --- | --- | --- |
| GitHub App | `github-app` | App ID, plus the full contents of `jenkins-github-app.pem`. Under **Advanced**, set **Owner** to the repo's owner, then click **Test Connection**. |
| Secret text | `github-webhook-secret` | The webhook secret |
| Secret text | `applitools-api-key` | Your Eyes **run** key |
| Secret text | `applitools-read-api-key` | Your Eyes **read** key |

The plugin uses `applitools-api-key` and `applitools-read-api-key` automatically, so your Jenkinsfile doesn't need to mention them.

Delete the `.pem` files once the GitHub App credential tests OK.

## 4. Configure Jenkins

**Manage Jenkins → System:**

- **Jenkins Location → Jenkins URL:** the address people use to reach Jenkins.
- **GitHub → Advanced → Shared secrets → Add →** `github-webhook-secret`. Jenkins then rejects webhooks that aren't signed by your App. Don't add a GitHub Server entry; the App manages its own webhook.
- **Applitools Eyes:**
  - **Eyes server URL:** leave the default unless you use a dedicated or on-prem Eyes.
  - **Designers:** Eyes login emails of the people who may approve design reviews. Leave empty to let any reviewer approve.
  - **Design review label:** defaults to `design-review`.

Save.

## 5. Create the pipeline job

**New Item → Multibranch Pipeline**, then:

- **Branch Sources → Add source → GitHub**
  - **Credentials:** `github-app`
  - **Repository HTTPS URL:** your repo
  - **Behaviors:**
    - *Discover branches:* "Exclude branches that are also filed as PRs"
    - *Discover pull requests from origin:* "The current pull request revision"
    - *Discover pull requests from forks:* remove it (fork builds would get your Eyes key)
- **Scan Multibranch Pipeline Triggers:** ✅ Periodically if not otherwise run, **1 minute** (a safety net for missed webhooks)

Save. Jenkins scans the repo; that's expected even before you add a Jenkinsfile.

## 6. Prepare your tests

The plugin hands everything to the Eyes SDK through `APPLITOOLS_*` environment variables (API key, batch, branch), so your test code doesn't need any of it. Two settings matter:

**Let the plugin judge visual changes.** By default, the SDKs fail the test run on any visual difference, which turns the build red before the plugin can mark it "changes to review".

- **Playwright** (`playwright.config.ts`):
  ```ts
  eyesConfig: {
    failTestsOnDiff: false,
    // ...your other settings
  }
  ```
- **Storybook:** run it with `--exitcode false`:
  ```bash
  npx eyes-storybook --exitcode false
  ```

**Don't set a batch or branch** in `applitools.config.js` or the Eyes config. The plugin sets them from the pull request, and its environment variables win anyway.

**Storybook only:** `eyes-storybook` drives Chrome through Puppeteer. Newer npm versions skip Puppeteer's Chrome download, so install it explicitly (see the Jenkinsfile below).

## 7. Add a Jenkinsfile

Put **all** your visual tests inside **one** `applitools { }` block. Everything inside it shares one Eyes batch, one pair of checks, and one design review.

**Declarative:**

```groovy
pipeline {
  agent { label 'playwright' }   // an agent with Node and browsers
  stages {
    stage('Install') {
      steps {
        sh 'npm ci'
        sh 'npx puppeteer browsers install chrome'   // only if you use Storybook
      }
    }
    stage('Visual tests') {
      steps {
        applitools {
          sh 'npx playwright test'
          sh 'npx eyes-storybook --exitcode false'   // only if you use Storybook
        }
      }
    }
  }
}
```

**Scripted:**

```groovy
node('playwright') {
  checkout scm
  sh 'npm ci'
  applitools {
    sh 'npx playwright test'
  }
}
```

Commit it to your default branch.

## 8. Require the checks before merging

Open a pull request first, so GitHub has seen both checks. Then go to **GitHub → repo Settings → Branches** (or **Rules → Rulesets**), protect your default branch, and **require status checks**:

- `Applitools / Visual tests`
- `Applitools / Design review`

## 9. Try it

1. **Open a pull request.** Within a minute, both checks appear. With no visual changes, Visual tests is ✅ and Design review is ➖ *skipped*.
2. **Make a visual change** and push. Visual tests turns ⚠️ *action required*, and the build turns yellow (unstable).
3. **Add the `design-review` label** to the PR. Design review turns ⚠️ *waiting for design review* right away.
4. **Click Details** on either check. It opens the batch in Eyes. Accept the changes and **save**.
5. Within a minute, both checks turn ✅. With a designers list, Design review reads *Approved by …*, and only a designer's approval counts.

Rejecting a change in Eyes turns both checks ❌.

## Reference

### `applitools` step options

All are optional.

| Option | Default | What it does |
| --- | --- | --- |
| `credentialsId` | `applitools-api-key` | Secret text credential with the Eyes run key |
| `readCredentialsId` | `applitools-read-api-key` (if it exists), else the run key | Key used to read results |
| `serverUrl` | global setting | Eyes server, for dedicated or on-prem |
| `onChanges` | `'UNSTABLE'` | Build result when there are changes to review: `'UNSTABLE'`, `'FAILURE'` or `'SUCCESS'` |
| `designReview` | from label / env / global | `true` or `false` to require or skip design review |
| `designReviewLabel` | `design-review` | PR label that requests design review |
| `designers` | global setting | Comma-separated Eyes emails who may approve |
| `checks` | `true` | Post the PR checks |
| `waitForResults` | `true` | Wait for Eyes results before finishing the block |
| `resultsTimeout` | `300` | Seconds to wait for tests still rendering |
| `batchName`, `batchId`, `branchName`, `parentBranchName` | from the PR | Overrides (support `${VAR}`) |
| `closeBatch` | `true` | Close the batch when the block ends |

**Requesting design review**, first match wins: the `designReview` option → the `APPLITOOLS_DESIGN_REVIEW=true|false` environment variable → the PR label → **Require design review on every pull request** in Manage Jenkins → System.

### `applitoolsResults()`

Returns the build's Eyes results for your own logic or notifications:

```groovy
def eyes = applitoolsResults()   // status: PASSED, CHANGES, FAILED or NO_RESULTS
if (eyes?.status == 'CHANGES') {
  echo "Visual changes to review: ${eyes.url}"
}
```

### Environment variables the plugin sets

`APPLITOOLS_API_KEY` (masked in logs), `APPLITOOLS_SERVER_URL`, `APPLITOOLS_BATCH_ID` (the PR's head commit), `APPLITOOLS_BATCH_NAME`, `APPLITOOLS_BATCH_SEQUENCE`, `APPLITOOLS_BRANCH_NAME`, `APPLITOOLS_PARENT_BRANCH_NAME`, `APPLITOOLS_DONT_CLOSE_BATCHES=true`.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| GitHub App **Test Connection: "Bad credentials"** | The App ID is wrong: use the number labeled **App ID**, not the Client ID or the installation ID. Or the key was pasted partially; paste the whole converted `.pem`. |
| Webhook deliveries show **405** | The webhook URL is missing the trailing slash: use `/github-webhook/`. |
| Webhook deliveries show **400** | Signature mismatch: the secret in the GitHub App and in `github-webhook-secret` differ. |
| Log: **"Eyes refused to share results (HTTP 403)"** | Run keys can't read results. Add the `applitools-read-api-key` credential. |
| Log: **"No Secret text credential with ID …"** | The credential ID doesn't match exactly (check spelling). |
| Build is **red** on visual changes instead of yellow | The SDK is failing the run on diffs. Set `failTestsOnDiff: false` (Playwright) or `--exitcode false` (Storybook). |
| Checks never appear on the PR | The job must use the GitHub App credential, and the **GitHub Checks** plugin must be installed. |
| **Build Now** on the multibranch job doesn't build | At the top level it only rescans the repo. Open the PR's job (**Pull Requests → PR-N**) and click Build Now there. |
| Inbound agent: **"incorrect secret"** | Copy the secret from **Manage Jenkins → Nodes → your agent** into the agent's `JENKINS_SECRET`. |
| Playwright: **"Request timed out, likely because it was stuck"**, all sessions aborted | Seen with Eyes NLP enabled in a containerized agent. Workaround: `nlpOptions: { enabled: false }` in `eyesConfig`. |
| Design review stays waiting after a designer accepted | The changes must be **saved** in Eyes, and the accepting user's email must be in **Designers**. |

## Local Jenkins: webhook relay

If GitHub can't reach your Jenkins, relay webhooks through [smee.io](https://smee.io). Nothing gets exposed: the relay holds an outbound connection.

1. Create a channel at https://smee.io/new.
2. Run the relay next to Jenkins (Podman or Docker). The `?relay=smee` keeps the trailing slash, which the relay would otherwise strip:
   ```bash
   podman run -d --name smee --restart=always docker.io/library/node:22-alpine npx --yes smee-client --url https://smee.io/YOUR_CHANNEL --target 'http://host.containers.internal:8080/github-webhook/?relay=smee'
   ```
3. Use the smee channel URL as the GitHub App's **Webhook URL**.

Only GitHub → Jenkins needs the relay. Everything else (cloning, posting checks, talking to Eyes) is outbound from Jenkins.
