# Set up Jenkins with Configuration as Code

Build the whole Jenkins side from files: plugins, admin user, agent, credentials, webhook signing, the Applitools settings and the job. Start the container and Jenkins comes up ready, with no setup wizard and no clicking through Manage Jenkins.

There are two versions of the main file, one per setup:

| | `jenkins.yaml` for | Same as |
| --- | --- | --- |
| **Recommended** | Multibranch Pipeline + GitHub App | [Setup guide](SETUP.md), steps 1, 3, 4 and 5 |
| **Token** | Plain Pipeline job + a GitHub token | [Setup without multibranch](SETUP-WITHOUT-MULTIBRANCH.md), steps 1, 3 and 5 |

**Both files below are tested:** the plugin's test suite loads them from this page, with placeholder secrets, and checks the result.

**Still done by hand, outside Jenkins:**
- Create the GitHub App ([setup guide, step 2](SETUP.md#2-create-a-github-app)) or the token ([step 2 without multibranch](SETUP-WITHOUT-MULTIBRANCH.md#2-create-a-github-token)).
- Create the Applitools keys (run, read, merge) in Eyes under **Admin → API Keys**.
- Prepare your tests and add the Jenkinsfile to the repo ([setup guide, steps 6 and 7](SETUP.md#6-prepare-your-tests)).
- Start the agent container ([Example agent](SETUP.md#example-agent-nodejs-and-playwright)).
- Branch protection ([step 8](SETUP.md#8-require-the-checks-before-merging)).

## 1. Lay out the files

```
jenkins/
├── Dockerfile
├── plugins.txt
├── applitools-jenkins.hpi      # the plugin, until it's published
├── jenkins.yaml                # step 3
├── compose.yaml
├── .env                        # secrets; never commit it
└── secrets/
    └── github-app.pem          # recommended setup only
```

**`Dockerfile`:**

```dockerfile
FROM docker.io/jenkins/jenkins:lts-jdk21

# No setup wizard: jenkins.yaml creates the admin user.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
ENV CASC_JENKINS_CONFIG=/etc/jenkins/jenkins.yaml

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
# .override: replace the installed copy on every rebuild, even at the same version.
COPY applitools-jenkins.hpi /usr/share/jenkins/ref/plugins/applitools-jenkins.jpi.override
```

**`plugins.txt`, recommended setup:**

```
configuration-as-code
job-dsl
workflow-aggregator
workflow-multibranch
github-branch-source
github-checks
credentials-binding
plain-credentials
checks-api
ionicons-api
```

**`plugins.txt`, token setup:** the same list, minus `github-branch-source` and `github-checks`, plus `git` and whatever triggers your job (for example `generic-webhook-trigger`, or `ghprb`).

The last four lines are the Applitools plugin's own dependencies. `jenkins-plugin-cli` can't read them from a local `.hpi`, so they're listed explicitly.

**`compose.yaml`** (works with `docker compose` and `podman compose`):

```yaml
services:
  jenkins:
    build: .
    container_name: jenkins
    restart: unless-stopped
    ports:
      - "8080:8080"
    env_file: .env
    volumes:
      - jenkins_home:/var/jenkins_home
      - ./jenkins.yaml:/etc/jenkins/jenkins.yaml:ro,Z
      - ./secrets:/run/secrets:ro,Z         # recommended setup only

volumes:
  jenkins_home:
```

The agent connects over the web port (WebSocket), so port 50000 isn't needed.

## 2. Put the secrets in `.env`

```bash
JENKINS_URL=http://localhost:8080/
JENKINS_ADMIN_PASSWORD=choose-one

APPLITOOLS_API_KEY=...          # run key
APPLITOOLS_READ_KEY=...         # read key
APPLITOOLS_MERGE_KEY=...        # merge key (tied to a team and a user)

# Recommended setup
GITHUB_APP_ID=...               # the number labeled "App ID", not the Client ID
GITHUB_WEBHOOK_SECRET=...       # the secret you gave the GitHub App

# Token setup
GITHUB_USER=...
GITHUB_TOKEN=...
```

For the recommended setup, save the GitHub App's key as `secrets/github-app.pem`, converted to the format Jenkins expects:

```bash
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in downloaded-key.pem -out secrets/github-app.pem
```

Jenkins reads `${VAR}` from the environment and `${readFile:...}` from files, so `jenkins.yaml` holds no secrets and can be committed.

## 3. Write `jenkins.yaml`

Replace `my-org` / `my-app` with your repository, and `team:Design` with your designers.

### Recommended: multibranch + GitHub App

<!-- casc:recommended -->
```yaml
jenkins:
  securityRealm:
    local:
      allowsSignup: false
      users:
        - id: admin
          password: "${JENKINS_ADMIN_PASSWORD}"
  authorizationStrategy:
    loggedInUsersCanDoAnything:
      allowAnonymousRead: false
  numExecutors: 0                    # builds run on the agent, not the controller
  nodes:
    - permanent:
        name: playwright
        labelString: playwright      # matches agent { label 'playwright' }
        remoteFS: /home/jenkins/agent
        numExecutors: 2
        launcher:
          inbound:
            webSocket: true

credentials:
  system:
    domainCredentials:
      - credentials:
          - gitHubApp:
              id: github-app
              appID: "${GITHUB_APP_ID}"
              privateKey: "${readFile:/run/secrets/github-app.pem}"
          - string:
              scope: GLOBAL
              id: github-webhook-secret
              secret: "${GITHUB_WEBHOOK_SECRET}"
          - string:
              scope: GLOBAL
              id: applitools-api-key
              secret: "${APPLITOOLS_API_KEY}"
          - string:
              scope: GLOBAL
              id: applitools-read-api-key
              secret: "${APPLITOOLS_READ_KEY}"
          - string:
              scope: GLOBAL
              id: applitools-merge-api-key
              secret: "${APPLITOOLS_MERGE_KEY}"

unclassified:
  location:
    url: "${JENKINS_URL}"
  gitHubPluginConfig:
    hookSecretConfigs:
      - credentialsId: github-webhook-secret   # reject webhooks not signed by your App
  applitools:
    designers: "team:Design"                   # Eyes team and/or login emails; omit to let anyone approve

jobs:
  - script: |
      multibranchPipelineJob('my-app') {
        branchSources {
          branchSource {
            source {
              github {
                id('my-app')
                repoOwner('my-org')
                repository('my-app')
                repositoryUrl('https://github.com/my-org/my-app')
                configuredByUrl(true)
                credentialsId('github-app')
                traits {
                  gitHubBranchDiscovery {
                    strategyId(1)            // exclude branches that are also filed as PRs
                  }
                  gitHubPullRequestDiscovery {
                    strategyId(2)            // the current pull request revision
                  }
                  // No fork discovery: fork builds would get your Eyes key.
                }
              }
            }
          }
        }
        triggers {
          periodicFolderTrigger {
            interval('1m')                   // safety net for missed webhooks
          }
        }
      }
```

### Token: plain Pipeline job

<!-- casc:token -->
```yaml
jenkins:
  securityRealm:
    local:
      allowsSignup: false
      users:
        - id: admin
          password: "${JENKINS_ADMIN_PASSWORD}"
  authorizationStrategy:
    loggedInUsersCanDoAnything:
      allowAnonymousRead: false
  numExecutors: 0
  nodes:
    - permanent:
        name: playwright
        labelString: playwright
        remoteFS: /home/jenkins/agent
        numExecutors: 2
        launcher:
          inbound:
            webSocket: true

credentials:
  system:
    domainCredentials:
      - credentials:
          - usernamePassword:
              scope: GLOBAL
              id: github-token               # checks out the repo, posts statuses, reads the PR
              username: "${GITHUB_USER}"
              password: "${GITHUB_TOKEN}"
          - string:
              scope: GLOBAL
              id: applitools-api-key
              secret: "${APPLITOOLS_API_KEY}"
          - string:
              scope: GLOBAL
              id: applitools-read-api-key
              secret: "${APPLITOOLS_READ_KEY}"
          - string:
              scope: GLOBAL
              id: applitools-merge-api-key
              secret: "${APPLITOOLS_MERGE_KEY}"

unclassified:
  location:
    url: "${JENKINS_URL}"
  applitools:
    designers: "team:Design"

jobs:
  - script: |
      pipelineJob('my-app-visual-review') {
        definition {
          cpsScm {
            scm {
              git {
                remote {
                  url('https://github.com/my-org/my-app.git')
                  credentials('github-token')
                }
                branch('*/main')
              }
            }
            scriptPath('Jenkinsfile')        // the Jenkinsfile from "Wire the step to your PR builds"
            lightweight(true)
          }
        }
      }
```

The Jenkinsfile then decides how the job is triggered and which PR it tests: see [Wire the step to your PR builds](SETUP-WITHOUT-MULTIBRANCH.md#5-create-the-job-and-wire-the-step-to-your-pr-builds). Build the job once by hand after Jenkins starts, so it picks up the triggers and parameters the Jenkinsfile declares.

### Every Applitools setting

Everything under `unclassified: applitools:` is optional. Leave out what you don't change.

| Key | Default | Same as Manage Jenkins → System |
| --- | --- | --- |
| `serverUrl` | `https://eyes.applitools.com` | Eyes server → Server URL |
| `credentialsId` | `applitools-api-key` | API keys → Run key |
| `readCredentialsId` | none: uses `applitools-read-api-key` if it exists | API keys → Read key |
| `mergeCredentialsId` | none: uses `applitools-merge-api-key` if it exists | API keys → Merge key |
| `githubReporting` | `AUTO` | Pull request results → Report to GitHub as (`AUTO`, `CHECKS` or `STATUSES`) |
| `designReviewCheck` | `true` | Post the "Applitools / Design review" result on every pull request |
| `designers` | none: anyone can approve | Design review → Designers |
| `designReviewLabel` | `design-review` | Design review → Label |
| `designReviewByDefault` | `false` | Require design review on every pull request |
| `mergeBaselines` | `true` | Baselines → merge when a pull request merges |
| `followBranchBuilds` | `false` | Advanced → Keep updating results for branch builds |

## 4. Start Jenkins

```bash
docker compose up -d --build
```

Sign in at your Jenkins URL as `admin`. Everything from `jenkins.yaml` is in place, including the job.

If Jenkins doesn't come up, `docker compose logs jenkins` names the line of `jenkins.yaml` it couldn't apply.

## 5. Connect the agent

Open **Manage Jenkins → Nodes → playwright**. The page shows the agent's secret. Start your agent container with it ([Example agent](SETUP.md#example-agent-nodejs-and-playwright)). The secret stays the same across restarts as long as the `jenkins_home` volume is kept.

## 6. Check it

Open **Manage Jenkins → Applitools setup**. Every row should be ✅, apart from "pull request webhooks" until the first one arrives. Then continue with the setup guide: [add the Jenkinsfile](SETUP.md#7-add-a-jenkinsfile), [require the checks](SETUP.md#8-require-the-checks-before-merging) and [try it](SETUP.md#9-try-it).

## Changing the configuration later

Edit `jenkins.yaml`, then **Manage Jenkins → Configuration as Code → Reload existing configuration** (or restart the container). Settings changed in the UI are overwritten on the next reload, so make lasting changes in the file.

**Already have a hand-configured Jenkins?** **Manage Jenkins → Configuration as Code → View Configuration** exports it as YAML; the Applitools section is under `unclassified: applitools:`. Copy the parts you need into the files above; the export includes encrypted secrets, which only that Jenkins can read, so replace them with `${VAR}` references.
