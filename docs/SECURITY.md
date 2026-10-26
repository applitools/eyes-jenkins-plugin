# Security review

What the Applitools Jenkins plugin can access, what was checked, what was found, and what was done about each finding. It's written for a security team deciding whether to install the plugin, so it lists the remaining risks and the limits of the review alongside the fixes.

| | |
| --- | --- |
| **Plugin** | `applitools-jenkins` 0.1.0-SNAPSHOT (beta) |
| **Reviewed** | Commit `32c8054` (22 October 2026) |
| **Fixes** | Commits `2eaebdc` and `0ac5f64` |
| **Checks re-run on** | `0ac5f64` (22–26 October 2026) |
| **Jenkins baseline** | 2.568.3, plugin BOM `bom-2.568.x` 7216.v6cb_72de2015d |

**Summary.** The automated checks are clean: no SpotBugs issues at the enforced level, all Maven Enforcer rules pass, and no dependency has a known vulnerability. The manual review found 13 issues: 1 high, 4 medium, 4 low and 4 informational. **11 are fixed, 1 is mitigated, and 1 is accepted as a design trade-off.** Each fix has automated tests. Two narrow residual risks remain; see [Remaining risks](#remaining-risks).

> **Who did this review.** The plugin's developers did this review with the tools listed below, helped by an AI code reviewer. The fixes were reviewed by their authors. **It isn't an independent third-party audit or a penetration test.** If your policy requires one, treat this document as input to it, not a replacement.
>
> The plugin is provided **as is**, without a maintenance commitment, support SLA or upgrade guarantee (see the [README](../README.md#support-and-maintenance)). That includes security fixes.

## What the plugin does, and what it can reach

The plugin adds an `applitools { }` step that runs your visual tests with Applitools Eyes. It then reports the results to GitHub pull requests and keeps those results updated as people review changes in Eyes.

**Secrets it uses.** All are Jenkins credentials. The plugin stores only their IDs, never their values.

| Credential | Used for | Where it's used |
| --- | --- | --- |
| Eyes **run** key | Uploading test results; closing the batch | Given to the tests inside the `applitools` block as `APPLITOOLS_API_KEY`; used by the controller to close the batch |
| Eyes **read** key | Reading results, approvals and Eyes teams | Controller only; never given to tests |
| Eyes **merge** key | Merging Eyes baselines when a PR merges | Controller only; never given to tests |
| GitHub App or token | Posting PR checks or commit statuses; reading PR labels and state | Controller only, through the job's own credentials |

**Network.** All requests go out from the Jenkins controller, through Jenkins' proxy settings, and only to:
- the Eyes server (default `https://eyes.applitools.com`), and
- the GitHub API (default `https://api.github.com`, or your GitHub Enterprise Server).

There is no telemetry and no other outbound traffic. The only inbound traffic is GitHub `pull_request` webhooks, through the GitHub plugin's existing `/github-webhook/` endpoint. The plugin doesn't add an endpoint of its own for them.

**Data stored in `$JENKINS_HOME`:**
- **Global settings:** credential IDs, URLs and options.
- **Per build:** the Eyes batch's results (test names, browsers, statuses and links to Eyes).
- **Review registry:** one entry per followed pull request, with job, build number, credential IDs, PR number and review state. There are at most 500 entries, and each is dropped after 14 days at the latest.

**Background work.** After a pull request's build finishes, a background task polls Eyes and GitHub to keep that PR's results current. It runs as Jenkins' system user, but it always looks up credentials as the *job's* own authentication, so it can't use credentials the job couldn't. It uses no build executors. Its limits are described in [OPERATIONS.md](OPERATIONS.md).

**Trust assumptions:**
- Jenkins administrators are trusted.
- People who can change a trusted Jenkinsfile, or configure a job, can already run code in builds and use the job's credentials. The plugin aims not to give them *more* than that. F2, F8 and F9 cover where that boundary sits.
- Code from forks is **not** trusted (see F5).
- Eyes and GitHub are trusted to authenticate themselves over TLS. Their responses are still treated as untrusted input: size-capped, never echoed into logs or PRs, and links filtered (F2 to F4).

## What was run

| Check | Tool | Result |
| --- | --- | --- |
| Static analysis, enforced in every build | SpotBugs (`spotbugs-maven-plugin` 4.10.4.1), as configured by the Jenkins plugin parent POM 6.2236.v12dd4c483242 | **0 issues** |
| Build and dependency rules | Maven Enforcer 3.6.3 with `jenkins-enforcer-rules`, `extra-enforcer-rules` and `restrict-imports-enforcer-rule` | **All pass** |
| Automated tests | JUnit with the Jenkins test harness, including Jenkins' injected tests | **175 pass**, 1 skipped by the harness. 31 of them were added for the fixes below. |
| Stricter static analysis with security rules | SpotBugs at maximum effort and lowest threshold, no omitted detectors, plus FindSecBugs 1.13.0 | 54 reports, none exploitable ([triage](#strict-static-analysis-triage)) |
| Known vulnerabilities in dependencies | OSV.dev, which includes GitHub Security Advisories and Jenkins security advisories, for all 123 runtime dependencies at the plugin's minimum versions | **0 advisories** (re-checked 26 October 2026) |
| Bundled libraries | Contents of `applitools-jenkins.hpi` | Only the plugin's own jar. Everything else comes from Jenkins core and plugins you already run, which Jenkins' own security warnings cover. |
| Manual source review | Every Java class and Jelly view, against the Jenkins plugin threat model. Dependency behavior was checked in the bytecode of the exact versions used. | 13 findings, below |
| Live check | A real pull request on a test repository, with Jenkins, GitHub and Eyes | Checks posted; label changes confirmed with GitHub through webhooks; the block ran as the encrypted-key Pipeline step; a closed PR was dropped without a baseline merge |

## Findings

Severity is as found, before fixes. It assumes a Jenkins set up as the [setup guide](SETUP.md) recommends, and names the access an attacker would need.

| ID | Finding | Severity | Status |
| --- | --- | --- | --- |
| [F1](#f1-forged-webhooks-could-merge-baselines-or-skip-design-review) | Forged webhooks could merge baselines or skip design review | High | Fixed |
| [F2](#f2-requests-from-the-controller-to-a-server-a-jenkinsfile-names) | Requests from the controller to a server a Jenkinsfile names | Medium | Fixed, with a residual risk |
| [F3](#f3-javascript-links-in-results) | `javascript:` links in results (stored XSS) | Medium | Fixed |
| [F4](#f4-unbounded-response-sizes) | Unbounded response sizes | Medium | Fixed |
| [F5](#f5-fork-pull-requests-received-the-run-key) | Fork pull requests received the run key | Medium | Fixed |
| [F6](#f6-run-key-in-plain-text-in-pipeline-state) | Run key in plain text in Pipeline state | Low | Fixed |
| [F7](#f7-fork-branch-names-could-collide-with-the-repositorys) | Fork branch names could collide with the repository's | Low | Fixed |
| [F8](#f8-jenkinsfiles-could-turn-design-review-off) | Jenkinsfiles could turn design review off | Informational | Mitigated (opt-in lock) |
| [F9](#f9-credentials-go-to-the-server-the-jenkinsfile-names) | Credentials go to the server the Jenkinsfile names | Informational | Accepted |
| [F10](#f10-plain-http-and-the-key-in-a-url) | Plain HTTP, and the key in a URL | Low | Fixed |
| [F11](#f11-baseline-merges-held-jenkins-shared-timer-threads) | Baseline merges held Jenkins' shared timer threads | Low | Fixed |
| [F12](#f12-pr-authored-text-and-reviewer-emails-in-github-checks) | PR-authored text and reviewer emails in GitHub checks | Informational | Fixed |
| [F13](#f13-the-setup-page-made-live-calls-on-page-load) | The setup page made live calls on page load | Informational | Fixed |

### F1. Forged webhooks could merge baselines or skip design review

**Severity:** High, on a Jenkins without a GitHub webhook secret. **Status:** fixed in `2eaebdc`.

**Issue.** The plugin acted on the content of `pull_request` webhooks without checking it. GitHub's plugin verifies webhook signatures only when a shared secret is configured, and none is by default. Anyone who could reach Jenkins could therefore forge an event for a followed pull request. A forged "merged" event could trigger an Eyes baseline merge between any two branches of that repository, overwriting approved baselines. A forged "unlabeled" event could mark design review as not required, which satisfies a required check. Replaying an event repeated the merge.

**Remediation:**
- A webhook is now only a prompt that a PR changed. Before acting, the plugin reads the PR from GitHub with the job's own credentials, and uses GitHub's answer for whether it merged, which branches, and which labels.
- Only the repository name and PR number are taken from the payload, and repository names GitHub couldn't produce are ignored.
- If GitHub can't be read, the webhook is ignored and regular polling continues.
- At most one baseline merge runs per build, and a replay after it finished does nothing.

**Tests:** `WebhookConfirmationTest`. A merge uses GitHub's branches, not the payload's. A forged "merged" event for an open PR does nothing. Webhooks are ignored when GitHub can't be read. A replayed merge runs once. Forged "labeled" and "unlabeled" events don't change design review.

**Still recommended:** configure a webhook secret (setup guide, step 4). The setup page warns when there isn't one.

### F2. Requests from the controller to a server a Jenkinsfile names

**Severity:** Medium. **Attacker needs:** write access to a trusted Jenkinsfile, or permission to configure a job. **Status:** fixed in `2eaebdc` and `0ac5f64`, with a residual risk.

**Issue.** The `serverUrl` and `githubApiUrl` step options choose where the controller sends requests. The controller would send them anywhere, including internal-only addresses such as a cloud metadata service. When a response wasn't JSON, the error message, which included the response body, went to the build log and the PR check. That let someone read internal services through Jenkins. Pipeline's sandbox otherwise keeps network calls on agents, so this went beyond what a Jenkinsfile author can normally do.

**Remediation:**
- Error messages show only the HTTP status and content type, never response content, for Eyes and GitHub alike.
- Server URLs must be `https` (see F10) and contain no user name or password. They may not resolve to link-local, unspecified or multicast addresses, or to known cloud metadata endpoints (`169.254.169.254`, `fd00:ec2::254`, `metadata.google.internal`).
- The step fails before running tests if a Jenkinsfile names such a URL, and every request is checked again before it's sent.
- GitHub repository names are validated before they're used in API paths.

**Tests:** `HostileServerTest`. Non-JSON responses aren't echoed. Reserved addresses are refused. The step refuses a metadata URL and a non-web GitHub API URL. Repository names are validated. A table of accepted and refused URLs is checked.

**Residual risk:** see [Remaining risks](#remaining-risks).

### F3. `javascript:` links in results

**Severity:** Medium. **Attacker needs:** as F2, plus a user clicking the link. **Status:** fixed in `2eaebdc`.

**Issue.** The Eyes batch and test links from the server's response were shown on the build page and in its sidebar. Jelly escaping prevents HTML injection but doesn't block a `javascript:` link. A server chosen by a Jenkinsfile could therefore plant a link that runs script in Jenkins when an administrator clicks "Review in Eyes". Jenkins' Content Security Policy blocks this when enforced, but it's report-only by default.

**Remediation:** links are kept only if they're absolute `http` or `https` URLs with a host. The check runs whenever a link is read, so it covers builds stored before the fix, the sidebar, PR checks and `applitoolsResults()`.

**Tests:** `ResultLinksTest`, covering `javascript:` in any case or with leading spaces, `data:` links, and relative links.

### F4. Unbounded response sizes

**Severity:** Medium. **Attacker needs:** as F2, or a compromised Eyes or GitHub Enterprise endpoint. **Status:** fixed in `2eaebdc`.

**Issue.** Responses were read fully into memory and fully decompressed, with no limit. A small gzip response could expand to gigabytes and exhaust the controller's memory. The background poller would then repeat the attempt every minute.

**Remediation:** responses are read through a reader that cancels the download past 16 MB, and decompression stops at the same limit. Eyes results for a 1,000-test batch are under 1 MB. An administrator can raise the limit with the system property `io.jenkins.plugins.applitools.EyesClient.maxResponseMegabytes`.

**Tests:** `HostileServerTest`. An 8 MB gzip bomb and an oversized plain response are both refused.

### F5. Fork pull requests received the run key

**Severity:** Medium. **Attacker needs:** a GitHub account, on a job that builds pull requests from forks. **Status:** fixed in `0ac5f64`.

**Issue.** Like `withCredentials`, the step gives the run key to the tests it wraps. That included builds of pull requests from forks, whose test code is untrusted and could send the key elsewhere. The key is masked in logs but not otherwise protected. GitHub Branch Source discovers fork PRs by default.

**Remediation:**
- The step detects fork PRs: multibranch jobs' `CHANGE_FORK`, or a GitHub Pull Request Builder author repository that isn't the target repository.
- By default it stops before the tests run, with a message that explains why.
- An administrator can allow fork PRs under **Manage Jenkins → System → Applitools Eyes → Advanced**.
- The setup guide also removes fork discovery from the job.
- The Generic Webhook Trigger example in the guide builds only PRs from the repository's own branches. With a generic trigger, the plugin can't tell forks apart by itself.

**Tests:** `SecurityHardeningTest`. Fork PRs are refused by default and their tests don't run. Fork detection works for GitHub Pull Request Builder. The guide's trigger filter was checked separately.

**Note:** with fork PRs allowed, the fork's test code gets the run key, and could upload results to any branch of that Eyes team. Allow it only if that's acceptable.

### F6. Run key in plain text in Pipeline state

**Severity:** Low. **Attacker needs:** read access to the controller's disk or backups. **Status:** fixed in `0ac5f64`.

**Issue.** Through Pipeline's generic wrapper support, a wrapper's environment variables are saved in plain text in a running build's state (`program.dat`), and aren't marked sensitive. `withCredentials`, by contrast, keeps secrets encrypted there.

**Remediation:**
- In Pipeline, `applitools` is now its own step, running the same logic. It keeps the key as an encrypted `Secret` in the build's saved state and declares it a sensitive variable, as `withCredentials` does.
- Because the key is declared sensitive, Pipeline also warns if a script puts it into a Groovy string.
- Jenkinsfiles don't change. Freestyle jobs keep the build wrapper, which doesn't save state between steps.

**Tests:** `SecurityHardeningTest`.
- While a build is paused inside the block, its saved state on disk is read. The key is found in plain text through the generic wrapper path, which proves the test can detect it, and is absent with the new step.
- The log shows `****` and the interpolation warning names `APPLITOOLS_API_KEY`.
- The step has exactly the wrapper's options.

**Verified live:** a pull request build ran the block as the new step.

### F7. Fork branch names could collide with the repository's

**Severity:** Low. **Status:** fixed in `0ac5f64`.

**Issue.** Eyes branch names are `owner/repo/<source branch>`. A fork's `main` branch would therefore test against, and save baselines onto, the repository's own `owner/repo/main` Eyes branch.

**Remediation:** fork PRs, when allowed, use `owner/repo/pr-<number>`. The Eyes branch used is recorded with the build, and the baseline merge uses that recorded branch as its source.

**Tests:** `SecurityHardeningTest`. Allowed forks get their own branch, which is recorded for the merge, and same-repository PRs keep their branch name.

### F8. Jenkinsfiles could turn design review off

**Severity:** Informational. **Attacker needs:** write access to a trusted Jenkinsfile. **Status:** mitigated in `0ac5f64`.

**Issue.** Step options, and the `APPLITOOLS_DESIGN_REVIEW` variable, let a Jenkinsfile turn design review off, choose its own designers, change the label, or skip PR results.

**Remediation:** a new administrator setting, **Jenkinsfiles can't loosen design review**. With it on, those Jenkinsfile settings are ignored, and the log says so. A Jenkinsfile can still *require* design review. It's off by default, because it changes what Jenkinsfiles may do.

**Tests:** `SecurityHardeningTest`. With the lock on, design review stays required and the Jenkinsfile's designers are ignored. Without it, the Jenkinsfile decides.

**Residual risk:** see [Remaining risks](#remaining-risks).

### F9. Credentials go to the server the Jenkinsfile names

**Severity:** Informational. **Status:** accepted as a design trade-off.

**Issue.** The Eyes keys and the GitHub token are sent to the configured `serverUrl` and `githubApiUrl`, which a Jenkinsfile can set. That's needed for dedicated, on-premises and GitHub Enterprise setups.

**Why it's accepted:** every credential is looked up in the job's own context, so a job can only send credentials it could already read with `withCredentials`. Since F2, the URLs are also checked.

**Recommendation:** don't fill `githubApiUrl` or `serverUrl` from build parameters. Anyone allowed to start the build could then choose where the token goes.

### F10. Plain HTTP, and the key in a URL

**Severity:** Low. **Status:** fixed in `0ac5f64`.

**Issue.** Nothing required `https`. Also, when an Eyes server rejected the key header while closing a batch, the plugin retried with the key in the URL's query string, where proxy and server logs can record it.

**Remediation:** keys go over plain `http` only to the Jenkins machine itself (loopback), unless an administrator allows http. The query-string retry is off unless an administrator enables it for an older on-premises Eyes server.

**Tests:** `SecurityHardeningTest` and `HostileServerTest`.

### F11. Baseline merges held Jenkins' shared timer threads

**Severity:** Low. **Status:** fixed in `2eaebdc` and `0ac5f64`.

**Issue.** Each baseline merge ran on Jenkins' shared timer pool and could wait up to 10 minutes. Combined with replayable forged events (F1), merges could tie up threads that Jenkins core and other plugins rely on.

**Remediation:** webhook follow-ups and merges run on two threads of the plugin's own, which end after a minute of idleness. Several webhooks in a row for one PR cost one GitHub read, and a merge runs at most once per build.

### F12. PR-authored text and reviewer emails in GitHub checks

**Severity:** Informational. **Status:** fixed in `0ac5f64`.

**Issue.** Test names come from test code, which a PR author controls, and were inserted into the check's Markdown, allowing links and images. Check titles named approving reviewers by their Eyes login email, which is public on public repositories.

**Remediation:** test, batch and environment names are escaped and show as plain text. A new administrator option, **Don't name reviewers in pull request results**, replaces names with text such as "Approved by a designer" and leaves out the designers list.

**Tests:** `SecurityHardeningTest`.

### F13. The setup page made live calls on page load

**Severity:** Informational. **Status:** fixed in `0ac5f64`.

**Issue.** Opening **Manage Jenkins → Applitools setup**, which requires the Administer permission, validated the keys with live Eyes requests. Simply following a link therefore triggered them. The requests changed nothing.

**Remediation:** the page shows a **Run checks** button. The checks run only on that POST request, with Jenkins' CSRF protection, and a GET request is refused.

**Tests:** `SetupPageTest`.

## Remaining risks

These are known and not fully closed:

1. **Blind requests to internal web servers (F2).** Someone who can change a trusted Jenkinsfile can still make the controller send requests to internal web servers that aren't link-local or metadata addresses. They can't read the responses. The URL check also resolves the host name before each request, so a DNS-rebinding attacker could race it. That needs both Jenkinsfile access and control of a DNS server, and the response still can't be read. **Mitigation:** restrict who can change trusted Jenkinsfiles, and restrict the controller's outbound network if your environment allows.
2. **Design review against people with repository write access (F8).** Even with the lock on, someone who can change a trusted Jenkinsfile can leave out the `applitools` step entirely. Required checks then block the merge, because the checks never appear. With access to the job's GitHub credentials, though, they could post a check with the same name themselves. That person already has write access to the repository. **Mitigation:** branch protection with required reviews, and limiting who can merge.

**Not verified:**
- **Slow responses:** every request has a 30-second timeout. It wasn't confirmed whether that also bounds a response body that arrives very slowly after its headers.
- **Redirects:** the HTTP client follows redirects. On the JDK whose code was inspected for the review (27), it drops the key and authorization headers on redirects to another host. This wasn't confirmed on other Java versions, such as the Java 21 runtime in the official Jenkins container image.

## Controls in place

| Control | Where |
| --- | --- |
| Credentials are looked up in the job's own context, as the job's authentication, during the build and later from the background poller. Running the poller as the system user doesn't widen what a job can use. | `ApplitoolsBuildWrapper`, `ReviewEvaluator`, `BaselineMerge`, `scm/GitHubRestClient` |
| No secrets are saved, only credential IDs and URLs. Client caches key on a hash of the key. | `review/PendingReview`, `scm/GitHubConnectionAction`, `ApplitoolsGlobalConfiguration`, `EyesClient` |
| The run key is masked in build logs and, in Pipeline, encrypted in saved state and declared sensitive. Read and merge keys never reach tests or logs. | `ApplitoolsStep`, `ApplitoolsBuildWrapper` |
| Credential drop-downs require POST and the standard permissions, and list only credentials visible to the job. | `ApplitoolsBuildWrapper`, `ApplitoolsGlobalConfiguration` |
| The setup page requires Administer, runs live checks only on POST, and shows credential IDs and validity, never values. | `setup/ApplitoolsSetupLink` |
| All Jelly views escape output by default; external links use `rel="noopener noreferrer"`. | All `.jelly` files |
| Server URLs are checked, responses are size-capped and never echoed, and links are filtered. | `ServerUrls`, `EyesClient`, `BatchResults` |
| Webhooks are confirmed with GitHub before any action. | `review/ReviewEvents` |
| Design review fails closed. If the designers team can't be resolved, nobody can approve. Approvals count only from the session that accepted the change. | `Designers`, `DesignReview`, `EyesClient` |
| Background work is bounded: at most 500 followed builds, 14-day expiry, a 50-second budget per pass, backoff on errors, and the plugin's own two threads for webhook work. | `ReviewWatcher`, `review/ReviewRegistry`, `review/ReviewEvents` |
| TLS uses the JVM's default certificate validation, and Jenkins' proxy settings apply. | `EyesClient` |

## Recommended configuration

For your security team's install checklist:

- [ ] **Sign webhooks.** Add a webhook secret under **Manage Jenkins → System → GitHub → Advanced → Shared secrets**. The setup page warns if it's missing.
- [ ] **Don't build fork pull requests.** Remove "Discover pull requests from forks" from multibranch jobs, and leave **Run visual tests on pull requests from forks** off.
- [ ] **Use least-privilege Eyes keys.** Tests get only the run key. Use a separate read key, and a merge key only if you want baseline merges.
- [ ] **Make the checks required** in branch protection, and require pull request reviews.
- [ ] **Consider the design review lock** if people who can change the Jenkinsfile shouldn't be able to loosen design review.
- [ ] **Consider hiding reviewer names** on public repositories.
- [ ] **Keep https.** Leave the http and query-string options off unless an on-premises Eyes server needs them.
- [ ] **Restrict the controller's outbound network** to your Eyes server and GitHub, if your environment supports it.
- [ ] **Run Manage Jenkins → Applitools setup → Run checks** after installing, and resolve every ⚠️ or ❌ row.

The [Configuration as Code guide](CONFIGURATION-AS-CODE.md#every-applitools-setting) lists every setting. The security options default to the safe choice.

## Strict static analysis triage

The stricter SpotBugs run reported 54 items on `0ac5f64`. None is exploitable:

| Pattern | Count | Assessment |
| --- | --- | --- |
| `PATH_TRAVERSAL_IN` | 1 | False positive: the registry file name is fixed (the class name) under `$JENKINS_HOME`, with no user input. |
| `CRLF_INJECTION_LOGS` | 7 | Low risk: log lines include repository names, PR numbers and Eyes merge summaries. Webhook repository names must match `owner/repo` before they're used or logged. |
| `IMPROPER_UNICODE` | 13 | Not a security issue: case-insensitive comparisons of labels, statuses, URL schemes, headers, repository and team names. None of them decides who may approve. |
| `USO_UNSAFE_METHOD_SYNCHRONIZATION` | 5 | Hardening only: the registry locks on its own instance. Exploiting that needs code already running inside Jenkins. |
| `EI_EXPOSE_REP`, `EI_EXPOSE_REP2` | 15 | Code quality: mutable objects shared inside the plugin. The Jenkins parent POM excludes this detector for the same reason. |
| Other: `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION`, `MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR`, `UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR`, `SE_TRANSIENT_FIELD_NOT_RESTORED` | 13 | Code style. The transient field holds the Pipeline step's options, which are needed only to start the block, so not saving them is deliberate. |

## Reproducing the checks

```bash
mvn verify
```

That runs the enforced SpotBugs and Enforcer checks and all tests. For the stricter scan, add FindSecBugs:

```bash
mvn dependency:get -Dartifact=com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0 -Dtransitive=false
```

```bash
mvn compile spotbugs:spotbugs -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.omitVisitors= -Dspotbugs.pluginList=$HOME/.m2/repository/com/h3xstream/findsecbugs/findsecbugs-plugin/1.13.0/findsecbugs-plugin-1.13.0.jar
```

The report is written to `target/spotbugsXml.xml`. The dependency list for an OSV check comes from:

```bash
mvn dependency:list -DincludeScope=runtime
```

## Scope and limits

- Covers the plugin's own code and how it uses Jenkins core and the plugins it depends on. It doesn't cover Jenkins itself, GitHub, Applitools Eyes, the Eyes SDKs your tests run, or your agents.
- Source review, automated analysis and one live run. Not a penetration test of your installation.
- Applies to commit `0ac5f64`. Later changes need their own review.
