# Applitools Jenkins Plugin

Chromatic-style visual review for Jenkins and GitHub, powered by Applitools Eyes. Wrap your visual tests in one step:

```groovy
applitools {
  sh 'npx playwright test'
}
```

Every pull request then gets two checks: **Applitools / Visual tests** and **Applitools / Design review**. Both link straight to the results in Eyes. Reviewers accept or reject changes in Eyes, and the checks update within a minute, with no re-run. Make them required, and nothing merges until the visual changes are approved.

- **Works with any Eyes SDK** (Playwright, Storybook, Cypress, Selenium…): the plugin passes batch, branch and key to the SDK through `APPLITOOLS_*` environment variables.
- **Design review on demand:** add the `design-review` label to a PR, and a designer has to approve its visual changes. Optionally, only listed designers count.
- **Results in Jenkins too:** a summary on the build page and `applitoolsResults()` for your own logic. Changes awaiting review keep the build green; the Applitools PR results are the gate.

## Getting started

You'll need Jenkins 2.568.3 or newer, a GitHub repository, and an Applitools Eyes account with visual tests in any SDK.

1. **[Download the plugin](https://github.com/applitools/eyes-jenkins-plugin/releases/latest)** (`applitools-jenkins.hpi`) and install it at **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin**, then restart Jenkins.
2. **Follow the [setup guide](docs/SETUP.md)** (about 30 minutes). It walks through the GitHub App, the Eyes keys, the job, wrapping your tests in `applitools { }`, and making the checks required. This is the recommended setup: a Multibranch Pipeline with a GitHub App.
3. **Open a pull request** with a visual change. Both checks appear on it; accept the change in Eyes and they turn green.

Other ways to set it up:

- **[Setup without multibranch](docs/SETUP-WITHOUT-MULTIBRANCH.md):** any job type (plain Pipeline or Freestyle), with just a GitHub token.
- **[Configuration as Code](docs/CONFIGURATION-AS-CODE.md):** the whole Jenkins side from one `jenkins.yaml`.

Before installing, your team may also want **[Resource usage and limits](docs/OPERATIONS.md)** and the **[Security review](docs/SECURITY.md)** (what the plugin can access, findings and fixes).

## Configuration

The defaults work once the Eyes run key is in place. **[Configuration](docs/CONFIGURATION.md)** lists every setting: the global settings in Manage Jenkins → System (with their Configuration as Code keys), the `applitools` step options, the environment variables the plugin sets, and `applitoolsResults()`.

## Configuration as Code

Every global setting can be set with the [Configuration as Code](https://plugins.jenkins.io/configuration-as-code/) plugin, under `unclassified: applitools:`. Leave out anything you don't change:

```yaml
unclassified:
  applitools:
    credentialsId: applitools-api-key
    designers: "team:Design, lead@example.com"
    lockDesignReview: true
    reviewPollSeconds: 30
```

The keys are listed in [Configuration](docs/CONFIGURATION.md#global-settings). The [Configuration as Code guide](docs/CONFIGURATION-AS-CODE.md) goes further: it builds the whole Jenkins side from one `jenkins.yaml` (plugins, credentials, the GitHub App, the agent and the job), with two complete files that the plugin's tests load on every build.

## Support and maintenance

This plugin is provided **as is**, with no commitment of any kind:

- **No maintenance commitment.** It may not receive fixes, updates or security patches, and it may stop being developed at any time.
- **No support or SLA.** There's no guaranteed response to questions, issues or pull requests, and no guaranteed time to resolve them.
- **No upgrade guarantee.** It may stop working with future versions of Jenkins, its plugins, GitHub or Applitools Eyes, and later versions of this plugin may change behavior or settings without a migration path.

Test it in your own environment before relying on it, and use it at your own risk. For what the plugin can access and how it was reviewed, see the [security review](docs/SECURITY.md). The software is licensed under the MIT License, which provides it without warranty of any kind.

## Develop

```bash
mvn hpi:run            # Jenkins with the plugin loaded at http://localhost:8080/jenkins
mvn verify             # build target/applitools-jenkins.hpi and run tests
```

**Release:** push a version tag (`git tag v0.2.0 && git push origin v0.2.0`). The [release workflow](.github/workflows/release.yml) runs the full build and tests with that version and publishes `applitools-jenkins.hpi` and its checksum on the Releases page. Creating the release on GitHub (**Releases → Draft a new release**, with a new `v…` tag) works too: the files are attached to it and its title is kept.

| Path | Role |
| --- | --- |
| `ApplitoolsBuildWrapper` | The `applitools { }` step: environment setup, batch close, results, checks |
| `scm/` | Works out the PR, branch and commit (GitHub, with an environment-variable fallback) and reads PR labels |
| `VisualTestsCheck`, `DesignReview` | The two PR checks |
| `ReviewWatcher`, `ReviewEvaluator`, `review/` | Follows reviews in Eyes after the build, and reacts to label webhooks |
| `EyesClient` | Eyes REST calls |
| `docs/PLAN.md` | Roadmap and design decisions |
| `docs/SPEC.md` | Behavior contract shared with the GitHub Action, plus Eyes/GitHub API notes |
