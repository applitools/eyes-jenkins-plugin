# Setup guide: visual review on every pull request

**This is the recommended setup:** a Multibranch Pipeline with a GitHub App. It gives you PR checks with details pages and instant reactions to labels and merges. **Can't use multibranch or the GitHub plugins?** See [Setup without multibranch](SETUP-WITHOUT-MULTIBRANCH.md): the same gate with just a token.

When you're done, every pull request gets two checks from Jenkins:

| Check | Passes when |
| --- | --- |
| **Applitools / Visual tests** | Every visual test passed, or its changes were accepted in Eyes |
| **Applitools / Design review** | Not requested (skipped), or a designer approved the changes in Eyes |

Both checks link straight to the results in Eyes. Accepting changes in Eyes turns them green within about a minute, with no re-run. Making them required in branch protection blocks the merge until then.

When the pull request merges, the plugin merges its Eyes baselines into the target branch, so accepted changes aren't flagged again there. The outcome appears as a third check, **Applitools / Baseline merge**.

Setup takes about 30 minutes. Do the steps in order.

**Prefer configuration as code?** Steps 1, 3, 4 and 5 fit in one `jenkins.yaml`, with the admin user and the agent too: see [Configuration as Code](CONFIGURATION-AS-CODE.md).

## Before you start

- [ ] **Jenkins 2.568 or newer** that GitHub can send webhooks to. If yours is on a laptop or behind a firewall, see [Local Jenkins: webhook relay](#local-jenkins-webhook-relay).
- [ ] **Admin access to the GitHub repo** (to install a GitHub App and set branch protection).
- [ ] **Applitools API keys:** a normal **run** key (uploads tests) and a **read** key (lets Jenkins read results). Create the read key in Eyes under **Admin → API Keys**. For merging baselines when a PR merges, you also need a **merge** key: in **Admin → API Keys**, create a key with the **merge** option. Merge keys are tied to both a team and a user, so every baseline merge is attributable. Run, read and write keys get refused.
- [ ] **A Jenkins agent that can run your tests:** Node.js plus the browsers your tests need. The examples use an agent labeled `playwright`; see [Example agent](#example-agent-nodejs-and-playwright) to build one.

## 1. Install the Jenkins plugins

**Manage Jenkins → Plugins → Available plugins**, then install:

- **GitHub Branch Source** (builds pull requests)
- **GitHub Checks** (posts checks to pull requests)
- **Pipeline** (if not already installed)

Then install the Applitools plugin: download `applitools-jenkins.hpi` from the [latest release](https://github.com/applitools/eyes-jenkins-plugin/releases/latest) and upload it at **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin**.

Restart Jenkins.

## 2. Create a GitHub App

Jenkins talks to GitHub as a GitHub App. This is recommended: results then appear as **checks**, with a details page on the PR. **Already using a personal access token for this job?** You can skip this step: see [Using a personal access token](#using-a-personal-access-token-instead-of-a-github-app).

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
| GitHub App | `github-app` | App ID, plus the full contents of `jenkins-github-app.pem`. Leave the repository access setting at its default (the owner is inferred from each job's repository; older plugin versions instead show an **Owner** field under **Advanced**: set it to the repo's owner). Click **Test Connection**. |
| Secret text | `github-webhook-secret` | The webhook secret |
| Secret text | `applitools-api-key` | Your Eyes **run** key |
| Secret text | `applitools-read-api-key` | Your Eyes **read** key |
| Secret text | `applitools-merge-api-key` | Your Eyes **merge** key (tied to a team and a user) |

The plugin finds these IDs automatically, so your Jenkinsfile doesn't need to mention them.

Delete the `.pem` files once the GitHub App credential tests OK.

## 4. Configure Jenkins

**Manage Jenkins → System:**

- **Jenkins Location → Jenkins URL:** the address people use to reach Jenkins.
- **GitHub → Advanced → Shared secrets → Add →** `github-webhook-secret`. Jenkins then rejects webhooks that aren't signed by your App. Don't add a GitHub Server entry; the App manages its own webhook.
- **Applitools Eyes:**
  - **Eyes server → Server URL:** leave the default unless you use a dedicated or on-prem Eyes.
  - **API keys:** **Run key**, **Read key** and **Merge key**. Pick the credentials from step 3, or leave Read and Merge empty: they then use `applitools-read-api-key` and `applitools-merge-api-key` automatically.
  - **Pull request results:** leave **Report to GitHub as** on *Automatic* (checks with a GitHub App, commit statuses with a token), and leave the Design review result on.
  - **Design review → Designers:** who may approve design reviews. Use an **Eyes team**, `team:Design`, and manage membership in Eyes (Admin → Teams), where designers already review. You can also list Eyes login emails, or mix both: `team:Design, lead@example.com`. Leave empty to let any reviewer approve.
  - **Design review → Label:** defaults to `design-review`. Tick **Require design review on every pull request** to skip the label.
  - **Design review → Jenkinsfiles can't loosen design review:** tick it if people who can edit the Jenkinsfile shouldn't be able to turn design review off, choose their own designers or change the label for their pull request. A Jenkinsfile can still *require* design review.
  - **Baselines:** merging a pull request's Eyes baselines into its target branch when it merges is on by default.
  - **Advanced:** the defaults are the safe choices. Change them only if you need to:
    - **Don't name reviewers in pull request results:** results otherwise show who approved, by Eyes login email. Consider it for public repositories.
    - **Run visual tests on pull requests from forks:** off by default, because a fork's test code would get your Eyes run key. While it's off, fork pull request builds stop with a message saying why. When on, each fork PR gets its own Eyes branch (`owner/repo/pr-<number>`).
    - **Allow http:// Eyes and GitHub URLs:** https is required except for servers on the Jenkins machine itself.
    - **Older on-premises Eyes: retry closing batches with the run key in the URL:** only if your Eyes server asks for it; URL parameters can end up in proxy logs.

Save.

## 5. Create the pipeline job

**New Item → Multibranch Pipeline**, then:

- **Branch Sources → Add source → GitHub**
  - **Credentials:** `github-app`
  - **Repository HTTPS URL:** your repo
  - **Behaviors:**
    - *Discover branches:* "Exclude branches that are also filed as PRs"
    - *Discover pull requests from origin:* "The current pull request revision"
    - *Discover pull requests from forks:* remove it. Fork builds run untrusted code; the plugin refuses to give them your Eyes key anyway, so they'd only fail.
- **Scan Multibranch Pipeline Triggers:** ✅ Periodically if not otherwise run, **1 minute** (a safety net for missed webhooks)

Save. Jenkins scans the repo; that's expected even before you add a Jenkinsfile.

**Recommended:** in GitHub, turn on **Settings → General → Automatically delete head branches**. Otherwise, once a PR merges, Jenkins builds its leftover branch as a regular branch, creating an extra Eyes batch for nobody to review.

## 6. Prepare your tests

The plugin hands everything to the Eyes SDK through `APPLITOOLS_*` environment variables (API key, batch, branch), so your test code doesn't need any of it. Two settings matter:

**Let the plugin judge visual changes.** By default, the Eyes SDKs fail the test command on any visual difference, which turns the build red before the plugin can mark it "changes to review".

- **Storybook and Cypress: nothing to do.** Inside the `applitools` block, the plugin sets `APPLITOOLS_EXITCODE=nodiffs` (Storybook: diffs don't fail the command, errors still do) and `APPLITOOLS_FAIL_CYPRESS_ON_DIFF=false`. Variables you've set yourself are left alone.
- **Playwright: one line** in `playwright.config.ts`. Its SDK has no environment switch, so make the setting conditional on the plugin's `APPLITOOLS_JENKINS_MANAGED` variable. Developers running tests locally still get failures on diffs:
  ```ts
  eyesConfig: {
    failTestsOnDiff: process.env.APPLITOOLS_JENKINS_MANAGED ? false : undefined,
    // ...your other settings
  }
  ```
- **Other SDKs** (Selenium, WebdriverIO…): pass `false` where your tests collect results (e.g. `runner.getAllTestResults(false)`).

**Don't set a batch or branch** in `applitools.config.js` or the Eyes config. The plugin sets them from the pull request, and its environment variables win anyway.

**Storybook only:** `eyes-storybook` drives Chrome through Puppeteer. If your agent uses the Playwright image, point Puppeteer at its Chromium with `PUPPETEER_EXECUTABLE_PATH` (see the Jenkinsfile below). That works on ARM agents too; Puppeteer's own Chrome download has no Linux ARM build, and newer npm versions skip it anyway. On other x86 agents, `npx puppeteer browsers install chrome` after `npm ci` also works.

**Make sure every suite actually reports.** List your Eyes app names with `expectApps` (or set a minimum with `expectTests`). If a suite reports nothing, for example because it couldn't start a browser, **Applitools / Visual tests** fails, even after the other suites' changes are accepted.

### Check your setup so far

Open **Manage Jenkins → Applitools setup** and click **Run checks**. It checks everything above, live:
- the Eyes server and all three keys (validated against Eyes with requests that change nothing)
- the GitHub Checks plugin and webhook signing
- whether pull request webhooks are arriving
- how each GitHub job will report (checks or commit statuses)

Every ⚠️ or ❌ row says how to fix it. Fix those before moving on.

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
      }
    }
    stage('Visual tests') {
      steps {
        // expectApps: your Eyes app names; the gate fails if one reports nothing.
        applitools(expectApps: ['My Web App', 'My Storybook']) {
          sh 'npx playwright test'
          // Storybook only. Uses the Playwright image's Chromium:
          sh 'PUPPETEER_EXECUTABLE_PATH=$(ls -d /ms-playwright/chromium-*/chrome-linux*/chrome | head -1) npx eyes-storybook'
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

Don't require `Applitools / Baseline merge`: it only appears after a pull request merges.

## 9. Try it

1. **Open a pull request.** Within a minute, both checks appear. With no visual changes, Visual tests is ✅ and Design review is ➖ *skipped*.
2. **Make a visual change** and push. Visual tests turns ⚠️ *action required*. The Jenkins build stays green: waiting on review isn't a failed run.
3. **Add the `design-review` label** to the PR. Design review turns ⚠️ *waiting for design review* right away.
4. **Click Details** on either check. It opens the batch in Eyes. Accept the changes and **save**.
5. Within a minute, both checks turn ✅. With designers set, Design review reads *Approved by …*, and only a designer's approval counts: someone in the Eyes team, or listed by email.

**Rejecting** a change in Eyes turns both checks ❌ (*"N of M failed"*, *"Changes requested"*) and keeps the merge blocked; the Jenkins build itself doesn't change. Rejecting doesn't alter the baseline, so push a fix and the next build is compared against the same baseline. If a reviewer changes their mind and accepts instead, the checks follow within 30 minutes (configurable under **Review polling**).

6. **Merge the pull request.** Within a minute or two, **Applitools / Baseline merge** appears on it: ✅ *Baselines merged into main*. The next build of `main` compares against the changes you accepted.

## Reference

The essentials are below. For every setting, including the global ones, see [Configuration](CONFIGURATION.md).

### `applitools` step options

All are optional. In Pipeline, the run key is handled like `withCredentials` handles secrets: masked in the log, encrypted in the build's saved state, and flagged if a script puts it into a Groovy string (`"${env.APPLITOOLS_API_KEY}"`); pass it to tools through the environment instead.

| Option | Default | What it does |
| --- | --- | --- |
| `credentialsId` | `applitools-api-key` | Secret text credential with the Eyes run key |
| `readCredentialsId` | `applitools-read-api-key` (if it exists), else the run key | Key used to read results |
| `serverUrl` | global setting | Eyes server, for dedicated or on-prem. Must be `https://` (or `http://`); link-local and cloud metadata addresses are refused |
| `onChanges` | `'SUCCESS'` | Build result when there are changes to review: `'SUCCESS'` (green: the PR results are the gate), `'UNSTABLE'` or `'FAILURE'`. Rejected or aborted tests and missing results always fail the build. |
| `mergeBaselines` | global setting | `true`/`false`: merge this job's Eyes baselines into the target branch's **when its PR merges** (only then) |
| `designReview` | from label / env / global | `true` or `false` to require or skip design review |
| `designReviewLabel` | `design-review` | PR label that requests design review |
| `designers` | global setting | Who may approve: `team:<Eyes team>` and/or Eyes login emails, comma-separated. Use double quotes if a name has an apostrophe: `designers: "team:Tim's Eyes Org"` |
| `checks` | `true` | Post the PR checks |
| `expectTests` | none | Minimum number of Eyes tests; fewer fails Visual tests and the build |
| `expectApps` | none | Eyes app names that must each report at least one test, e.g. `['Web', 'Storybook']` |
| `judgeDiffs` | `true` | Tell SDKs that support it (Storybook, Cypress) not to fail on diffs; `false` keeps the SDKs' behavior |
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

Branch names follow Eyes' `owner/repo/branch` convention, so baselines line up with the Eyes GitHub integration if you also use it.

`APPLITOOLS_JENKINS_MANAGED=true` (lets test configs detect the plugin), `APPLITOOLS_EXITCODE=nodiffs` and `APPLITOOLS_FAIL_CYPRESS_ON_DIFF=false` (unless you set them, or `judgeDiffs: false`), `APPLITOOLS_API_KEY` (masked in logs), `APPLITOOLS_SERVER_URL`, `APPLITOOLS_BATCH_ID` (the PR's head commit), `APPLITOOLS_BATCH_NAME`, `APPLITOOLS_BATCH_SEQUENCE`, `APPLITOOLS_BRANCH_NAME`, `APPLITOOLS_PARENT_BRANCH_NAME`, `APPLITOOLS_DONT_CLOSE_BATCHES=true`.

## Troubleshooting

**Start with Manage Jenkins → Applitools setup → Run checks.** It catches most of the problems below and says how to fix each one.

| Symptom | Cause and fix |
| --- | --- |
| GitHub App **Test Connection: "Bad credentials"** | The App ID is wrong: use the number labeled **App ID**, not the Client ID or the installation ID. Or the key was pasted partially; paste the whole converted `.pem`. |
| Webhook deliveries show **405** | The webhook URL is missing the trailing slash: use `/github-webhook/`. |
| Webhook deliveries show **400** | Signature mismatch: the secret in the GitHub App and in `github-webhook-secret` differ. |
| Log: **"Eyes refused to share results (HTTP 403)"** | Run keys can't read results. Add the `applitools-read-api-key` credential. |
| Log: **"This pull request comes from a fork"** | Fork PRs don't get your Eyes key by default. Remove fork discovery from the job (step 5), or let an admin allow fork PRs under **Manage Jenkins → System → Applitools Eyes → Advanced**. |
| Log: **"must use https://"** | Keys aren't sent over plain http to other machines. Use the server's https address, or let an admin allow http under **Advanced**. |
| Log: **"Design review is locked"** | An admin ticked **Jenkinsfiles can't loosen design review**, so the Jenkinsfile's `designReview: false`, `designers` and `designReviewLabel` are ignored. |
| Log: **"… points at a reserved address"** or **"must start with https://"** | `serverUrl` or `githubApiUrl` names a link-local or cloud metadata address, or isn't a web URL. Use your Eyes server's or GitHub's address. |
| Log: **"Response larger than 16 MB; not read"** | A server returned far more than any Eyes or GitHub response. Check `serverUrl`. For a legitimately huge batch, raise the limit with the system property `io.jenkins.plugins.applitools.EyesClient.maxResponseMegabytes`. |
| Log: **"No Secret text credential with ID …"** | The credential ID doesn't match exactly (check spelling). |
| Build is **red** on visual changes | An SDK is failing the command on diffs. Playwright: add the `failTestsOnDiff` line from step 6. Storybook/Cypress: check you haven't set `APPLITOOLS_EXITCODE` / `APPLITOOLS_FAIL_CYPRESS_ON_DIFF` yourself, and that `judgeDiffs` isn't `false`. |
| **Visual tests: "Missing results: no tests from …"** | A suite reported nothing to Eyes. Look in the build log for that suite's error (e.g. a browser that wouldn't start). |
| Checks never appear on the PR | With a GitHub App credential, the **GitHub Checks** plugin must be installed. With a token, look for **commit statuses** instead (same names), and make sure the token can write them. |
| **Build Now** on the multibranch job doesn't build | At the top level it only rescans the repo. Open the PR's job (**Pull Requests → PR-N**) and click Build Now there. |
| Inbound agent: **"incorrect secret"** | Copy the secret from **Manage Jenkins → Nodes → your agent** into the agent's `JENKINS_SECRET`. |
| Playwright: **"Request timed out, likely because it was stuck"**, all sessions aborted | Seen with Eyes NLP enabled in a containerized agent. Workaround: `nlpOptions: { enabled: false }` in `eyesConfig`. |
| Storybook: **"Failed to launch the browser process"** / `qemu-x86_64-static` | Puppeteer downloaded x86 Chrome on an ARM agent. Set `PUPPETEER_EXECUTABLE_PATH` to the Playwright image's Chromium (see step 7). |
| Storybook stories missing from the Eyes batch | `eyes-storybook` failed before testing. Its step now fails the build (the plugin uses `nodiffs`, not `false`); look for errors right after "Storybook was started". Use `expectApps` to catch it in the PR check too. |
| **Baseline merge: "Eyes refused the baseline merge"** | Only **merge** keys can merge baselines (run, read and write keys get HTTP 403). Add one as `applitools-merge-api-key`, then retry: **GitHub App → Advanced → Recent Deliveries**, find the PR's `pull_request` (`closed`) delivery, click **Redeliver**. |
| **Baseline merge: "N baseline conflicts"** | The same checkpoints also changed on the target branch. Resolve them in Eyes under **Compare & merge branches**. |
| **No "Baseline merge" result on a merged PR** | Look in **Manage Jenkins → System Log** for lines about *Eyes baselines*: they say why a merge didn't run (for example, the PR's build was deleted before Jenkins learned the PR merged, or a Jenkinsfile set `checks: false` or `waitForResults: false`, which the build log also warns about). Then check that GitHub's `closed` webhook reached Jenkins (**GitHub App → Advanced → Recent Deliveries**). If the merge didn't happen, merge in Eyes under **Compare & merge branches**: `owner/repo/<PR branch>` → `owner/repo/<target branch>`. |
| Design review stays waiting after a designer accepted | The changes must be **saved** in Eyes, and the accepting user must be in a designers team (or listed by email). **Manage Jenkins → Applitools setup** shows exactly who counts. |
| **Design review: "Designers couldn't be determined"** | An Eyes team in Designers wasn't found (check the spelling against Eyes → Admin → Teams), or the read key can't list teams. Nobody can approve until it's fixed. |

## Configuration as Code

Steps 1, 3, 4 and 5, plus the admin user and the agent, fit in one `jenkins.yaml`. See [Configuration as Code](CONFIGURATION-AS-CODE.md) for the files, tested by the plugin's test suite.

## Using a personal access token instead of a GitHub App

If your multibranch job already connects to GitHub with a personal access token, the plugin posts the same three results as **commit statuses** instead of checks. They have the same names (`Applitools / Visual tests`, `Applitools / Design review`, `Applitools / Baseline merge`), link to the same Eyes results, and can be required in branch protection the same way. You don't need the GitHub Checks plugin.

What's different:
- **Credential:** add the token as a **Username with password** credential (username: your GitHub username, password: the token) and select it under **Branch Sources → GitHub → Credentials**. GitHub Branch Source doesn't accept Secret text.
- **Token permissions:** classic token: `repo` (or `repo:status` plus read access for public repos). Fine-grained token: **Commit statuses: read and write**, **Pull requests: read**, **Contents: read**.
- **Webhook:** without an App, add the webhook to the repo yourself: **repo Settings → Webhooks → Add webhook**, payload URL `https://<your-jenkins>/github-webhook/`, content type `application/json`, the same secret as `github-webhook-secret`. Select **Pull requests** and **Pushes**.
- **Waiting for review** shows as a yellow *pending* status (there's no "action required" for statuses). It still blocks the merge.

**Manage Jenkins → System → Applitools Eyes → Report to GitHub as** is **AUTO** by default: checks for GitHub App jobs, statuses for token jobs. Set it to **CHECKS** or **STATUSES** to force one.

## How much it costs your Jenkins

Very little, and it's bounded: no thread per review, at most 500 followed builds (~1 MB of memory), polling that slows from every minute to every 30 minutes as reviews age (both configurable), and gzipped traffic of about 400 KB per PR reviewed within a day. A hung build costs nothing. See [Resource usage and limits](OPERATIONS.md) for the calculations and tuning options.

## Local Jenkins: webhook relay

If GitHub can't reach your Jenkins, relay webhooks through [smee.io](https://smee.io). Nothing gets exposed: the relay holds an outbound connection.

1. Create a channel at https://smee.io/new.
2. Run the relay next to Jenkins (Podman or Docker). The `?relay=smee` keeps the trailing slash, which the relay would otherwise strip:
   ```bash
   podman run -d --name smee --restart=always docker.io/library/node:22-alpine npx --yes smee-client --url https://smee.io/YOUR_CHANNEL --target 'http://host.containers.internal:8080/github-webhook/?relay=smee'
   ```
3. Use the smee channel URL as the GitHub App's **Webhook URL**.

Only GitHub → Jenkins needs the relay. Everything else (cloning, posting checks, talking to Eyes) is outbound from Jenkins.

## Example agent: Node.js and Playwright

The examples run tests on an agent labeled `playwright`. Any agent with Node.js and browsers works; this one is a container with Node 22 and Playwright's Chromium, Firefox and WebKit. Its browsers live in `/ms-playwright`, which is where the Storybook line in step 7 finds Chromium.

**`Dockerfile`:**

```dockerfile
FROM docker.io/jenkins/inbound-agent:latest-jdk21

# Match PLAYWRIGHT_VERSION to @playwright/test in your package.json.
ARG NODE_MAJOR=22
ARG PLAYWRIGHT_VERSION=latest

USER root
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
 && curl -fsSL https://deb.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && npx -y playwright@${PLAYWRIGHT_VERSION} install --with-deps chromium firefox webkit \
 && chmod -R a+rx ${PLAYWRIGHT_BROWSERS_PATH} \
 && rm -rf /var/lib/apt/lists/* /root/.npm
USER jenkins
```

**`compose.yaml`:**

```yaml
services:
  agent:
    build: .
    container_name: jenkins-agent-playwright
    restart: unless-stopped
    init: true                 # reaps leftover browser processes
    shm_size: "2gb"            # Chromium crashes with the default 64 MB /dev/shm
    environment:
      - JENKINS_URL=${JENKINS_URL}               # as seen from the agent container
      - JENKINS_AGENT_NAME=playwright
      - JENKINS_SECRET=${JENKINS_AGENT_SECRET}
      - JENKINS_WEB_SOCKET=true                  # connects over the web port
      - JENKINS_AGENT_WORKDIR=/home/jenkins/agent
      - CI=true
```

1. In Jenkins: **Manage Jenkins → Nodes → New Node**, name `playwright`, **Permanent Agent**. Set **Labels** to `playwright`, **Remote root directory** to `/home/jenkins/agent`, **Launch method** to *Launch agent by connecting it to the controller* with **Use WebSocket** ticked. (With [Configuration as Code](CONFIGURATION-AS-CODE.md), the node already exists.)
2. Open the node's page and copy the secret it shows.
3. Put `JENKINS_URL` and `JENKINS_AGENT_SECRET` in a `.env` file next to `compose.yaml`, then `docker compose up -d --build` (or `podman compose`).
   - With Jenkins in another container on the same machine, use `http://host.docker.internal:8080/` (Docker) or `http://host.containers.internal:8080/` (Podman).

The node shows as connected within a few seconds. **"incorrect secret"** in the agent's log means the secret or the node name doesn't match.

Running Jenkins with one executor on the built-in node instead works too, for a trial: use `agent any` in the Jenkinsfile and install Node.js and browsers in the Jenkins container.

