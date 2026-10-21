# Setup effort: this plugin vs Chromatic (Jenkins + GitHub)

*Researched 2026-10-20 against Chromatic's docs (Jenkins, Quickstart, UI Review, mandatory PR checks) and our own setup guide, as built and tested today.*

## Bottom line

**Today, our setup is harder than Chromatic's, but only on one axis: getting checks onto GitHub.** Everything else is at parity or better.

- **If Jenkins already builds GitHub PRs with a GitHub App** (the standard modern Jenkins + GitHub setup), we're **roughly on par**. Chromatic: get a token, add one stage, require the checks. Us: install the plugin, add two keys, wrap the tests, require the checks. About 10–15 minutes each.
- **If Jenkins doesn't use a GitHub App yet** (for example, it builds PRs with a personal token, or with the older GitHub Pull Request Builder plugin), **we're clearly harder:** about 30 minutes vs about 10. Chromatic posts its PR checks itself from its own web app, so Jenkins never talks to GitHub. Our checks come from Jenkins, which requires a GitHub App, the GitHub Checks plugin and a multibranch job.

Five changes would bring us to parity in both cases, and three of them make us easier than Chromatic. See [Closing the gap](#closing-the-gap).

## Step by step

Both columns assume Jenkins already builds pull requests. Chromatic's Jenkins guide also expects this (it recommends the GitHub Pull Request Builder plugin), so neither product removes that step.

| Step | Chromatic | This plugin | Verdict |
| --- | --- | --- | --- |
| **Account and project** | Sign in with GitHub, create a project **linked to the repo**. That link is what posts the PR checks. | Applitools account (already exists for Eyes customers). | Parity |
| **Secrets** | 1 project token → Jenkins credential | 2 keys (run and read) → 2 Jenkins credentials | **Chromatic easier.** Eyes run keys get HTTP 403 when reading results. |
| **Install** | `npm install --save-dev chromatic` | Install the Jenkins plugin, plus the Eyes SDK you already use | Parity once we're on the Jenkins update center |
| **Jenkinsfile** | `sh 'npx chromatic'` with the token in `environment`. Playwright/Cypress also need `archiveArtifacts` and `CHROMATIC_ARCHIVE_LOCATION`. | `applitools { sh 'npx playwright test' }` | **Us easier**, especially beyond Storybook |
| **Don't fail CI on changes** | `--exit-zero-on-changes` (default is to fail) | Storybook, Cypress: automatic. Playwright: one config line. | **Us easier** for Storybook and Cypress; parity for Playwright |
| **PR checks** | Automatic from the linked project | Needs a **GitHub App** with Checks: write, the **GitHub Checks** plugin, and a multibranch job using that App | **Chromatic easier.** This is the main gap. |
| **Webhooks** | Not needed for checks | GitHub → Jenkins webhook (already there if Jenkins builds PRs; adds instant label reactions) | Parity if PR builds exist |
| **Reviewers** | In Chromatic's UI: default reviewers, assign per review, or by component owner | An Eyes team (`team:Design`), managed in Eyes where designers review; or emails | **Parity** for "who can approve"; Chromatic still has per-review assignment and component owners |
| **Requesting review** | UI Review is on or off for the whole project | Per PR with a label, or always, or never | **Us better:** opt-in per PR, applied instantly |
| **Required checks** | Branch protection: pick the Chromatic checks | Branch protection: pick the two Applitools checks | Parity |
| **Storybook** | Builds Storybook; captures in Chromatic's cloud | `eyes-storybook` needs Chrome on the agent. On ARM agents, Puppeteer's download doesn't work and you must point it at another Chromium. | **Chromatic easier** (one less agent requirement) |
| **Silent failures** | A Storybook build error fails the CI step | Errors still fail the step (`nodiffs`), and `expectApps` fails the gate if a suite reports nothing | **Us safer:** the gate itself checks every suite reported |
| **After merge** | Accepted baselines carry over to the target branch automatically | **Not built yet** (plan Phase 5): the next `main` build may show the same changes again | **Chromatic better.** A functional gap, not just setup. |

### Effort by starting point

| Starting point | Chromatic | This plugin today |
| --- | --- | --- |
| Jenkins builds PRs with a GitHub App | ~10 min | ~15 min (two keys, plugin install, one block, SDK flag) |
| Jenkins builds PRs another way (token, ghprb) | ~10 min | ~30 min (also create a GitHub App, switch to a multibranch job, install GitHub Checks) |
| Jenkins doesn't build PRs yet | ~25 min (PR builds + Chromatic) | ~30 min (our guide includes the PR-build setup) |

## Where we're already better

- **No second GitHub integration.** Chromatic needs repo access granted to an outside SaaS. We use the GitHub App Jenkins already has, so security reviews are simpler.
- **Any Eyes SDK, one step.** Playwright, Cypress, Selenium or Storybook: wrap the command. Chromatic's non-Storybook flows need artifact archiving and archive-location variables.
- **Design review per PR, on demand,** by label, with instant updates and no rebuild.
- **Results in Jenkins too:** build page summary and `applitoolsResults()` for custom logic, without turning builds red for changes awaiting review.
- **Real cross-browser and device rendering** through the Ultrafast Grid (Chromatic also offers multiple browsers, so this is roughly parity).

## Closing the gap

Ranked by how much setup each removes.

| # | Change | Removes | Makes us | Effort |
| --- | --- | --- | --- | --- |
| 1 | ✅ **Done (2026-10-20).** **Commit-status fallback.** When the job has no GitHub App, post the two results as commit statuses using whatever GitHub credential the job already has (statuses work with personal tokens; checks don't). | The GitHub App requirement for teams using tokens | **At parity** in the "Jenkins builds PRs another way" case | Medium (plugin only) |
| 2 | **One key.** Read results with the run key when Eyes allows it, or have Applitools issue a single "CI key" that can run and read. | The second credential | **At parity** on secrets | Small in the plugin; needs an Eyes product decision |
| 3 | ✅ **Done (2026-10-20).** **Setup checklist page in Jenkins** that checks each piece live: keys valid, GitHub App permissions, webhook received, last check posted, with a fix link for each failure. | Most troubleshooting | **Easier than Chromatic**, which has no CI-side diagnostics | Medium (plan Phase 6) |
| 4 | ✅ **Done (2026-10-20)** for Storybook and Cypress (automatic); Playwright needs one conditional config line (no SDK env switch; worth raising with the SDK team). **Turn off fail-on-diff automatically:** if the SDKs honor an environment variable for it, the plugin sets it inside the block. *Needs checking per SDK.* | The SDK flag | **Easier than Chromatic** (they need `--exit-zero-on-changes`) | Small, if supported |
| 5 | ✅ **Done (2026-10-21), as an Eyes team** (`team:Design`): GitHub teams give logins that can't be mapped to Eyes users, while Eyes teams use the same identity as approvals. **Designers from a GitHub team** (e.g. `@org/design`) or an Eyes team, instead of a Jenkins list. | Admin-managed reviewer lists | **At parity** with Chromatic's default reviewers | Medium |
| 6 | **Baseline merge after PR merge** (plan Phase 5). | Re-reviewing the same changes on `main` | **At parity** functionally | Medium; depends on the Eyes merge API |
| 7 | ✅ **JCasC done (2026-10-21):** the whole setup in one tested `jenkins.yaml`; update-center publishing still to do. **Publish to the Jenkins update center** and provide a JCasC snippet for the whole global config. | Manual `.hpi` upload, form clicking | At parity / easier for config-as-code shops | Small |
| 8 | ✅ **Done (2026-10-20).** **Catch suites that ran nothing:** an optional `expectTests:` minimum (or per-app counts) on the step; fewer results fails Visual tests. Pairs with #4, which would remove the need for `--exitcode false`. | Silent false passes | **Safer than Chromatic** | Small |

**Recommended order:** 6 (correctness first), then 1 and 2 (remove the two biggest setup gaps), then 3 and 4 (pull ahead of Chromatic).

## Open questions to settle

1. **Does Eyes merge branch baselines into the parent when a PR merges,** and can the plugin trigger it? This decides how big #6 is.
2. **Can a single Eyes key both run and read?** If an admin key can, the plugin may already work with one credential; the read key would then be a least-privilege option rather than a requirement.
3. **Do the Playwright, Cypress and Storybook SDKs read an environment variable for fail-on-diff / exit code?** That decides #4.
4. **Chromatic, to confirm by hand:** whether accepting changes updates Chromatic's PR check without re-running CI. Their docs don't say. Ours does.
