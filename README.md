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
- **Results in Jenkins too:** a summary on the build page, `applitoolsResults()` for your own logic, and yellow (unstable) builds for changes awaiting review.

**→ [Setup guide](docs/SETUP.md)** (recommended: multibranch + GitHub App, about 30 minutes) · **[Setup without multibranch](docs/SETUP-WITHOUT-MULTIBRANCH.md)** (any job type, just a token) · **[Resource usage and limits](docs/OPERATIONS.md)**

## Develop

```bash
mvn hpi:run            # Jenkins with the plugin loaded at http://localhost:8080/jenkins
mvn verify             # build target/applitools-jenkins.hpi and run tests
```

| Path | Role |
| --- | --- |
| `ApplitoolsBuildWrapper` | The `applitools { }` step: environment setup, batch close, results, checks |
| `scm/` | Works out the PR, branch and commit (GitHub, with an environment-variable fallback) and reads PR labels |
| `VisualTestsCheck`, `DesignReview` | The two PR checks |
| `ReviewWatcher`, `ReviewEvaluator`, `review/` | Follows reviews in Eyes after the build, and reacts to label webhooks |
| `EyesClient` | Eyes REST calls |
| `docs/PLAN.md` | Roadmap and design decisions |
| `docs/SPEC.md` | Behavior contract shared with the GitHub Action, plus Eyes/GitHub API notes |
