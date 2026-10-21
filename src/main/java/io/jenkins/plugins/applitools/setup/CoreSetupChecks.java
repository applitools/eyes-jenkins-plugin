package io.jenkins.plugins.applitools.setup;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration;
import io.jenkins.plugins.applitools.Designers;
import io.jenkins.plugins.applitools.EyesClient;
import io.jenkins.plugins.applitools.review.PendingReview;
import io.jenkins.plugins.applitools.review.ReviewRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/** Jenkins and Eyes: Jenkins URL, Eyes server, the three API keys (validated without side effects), reviews. */
@Extension(ordinal = 100)
public class CoreSetupChecks extends SetupCheck {

    @Override
    public List<SetupItem> run() throws InterruptedException {
        List<SetupItem> items = new ArrayList<>();
        ApplitoolsGlobalConfiguration config = ApplitoolsGlobalConfiguration.get();
        items.add(jenkinsUrl());

        String server = config.getServerUrl();
        String runId = config.getCredentialsId();
        String runKey = secret(runId);
        if (runKey == null) {
            items.add(SetupItem.problem("Eyes run key", "No Secret text credential with ID \"" + runId + "\".",
                    "Add your Eyes run key under Manage Jenkins → Credentials with that ID."));
            return items;
        }
        EyesClient.KeyCheck run;
        try {
            run = new EyesClient(server, runKey).checkRead();
        } catch (IOException e) {
            items.add(SetupItem.problem("Eyes server", "Can't reach " + server + ": " + e.getMessage(),
                    "Check the Eyes server URL and Jenkins' proxy settings."));
            return items;
        }
        items.add(SetupItem.ok("Eyes server", server + " is reachable."));
        items.add(run == EyesClient.KeyCheck.INVALID
                ? SetupItem.problem("Eyes run key", "Eyes doesn't recognize the key in \"" + runId + "\".",
                        "Copy a run key from Eyes → Admin → API Keys into that credential.")
                : SetupItem.ok("Eyes run key", "\"" + runId + "\" is valid."));

        String readId = config.getReadCredentialsId() != null
                ? config.getReadCredentialsId()
                : ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID;
        items.add(keyRow("Eyes read key", readId, server, false,
                "Without one, Jenkins can't read results: no build summary, and the PR checks fail with HTTP 403.",
                "Create a read key in Eyes → Admin → API Keys and add it as Secret text \"" + readId + "\"."));

        if (config.isMergeBaselines()) {
            String mergeId = config.getMergeCredentialsId() != null
                    ? config.getMergeCredentialsId()
                    : ApplitoolsGlobalConfiguration.DEFAULT_MERGE_CREDENTIALS_ID;
            items.add(keyRow("Eyes merge key", mergeId, server, true,
                    "Without one, baselines aren't merged when a PR merges, and accepted changes show up again on"
                            + " the target branch.",
                    "Create a key with the merge option (tied to a team and a user) in Eyes → Admin → API Keys,"
                            + " and add it as Secret text \"" + mergeId + "\"."));
        } else {
            items.add(SetupItem.info("Baseline merge", "Turned off in Manage Jenkins → System → Applitools Eyes."));
        }

        items.add(designersRow(config, server, secret(readId) != null ? secret(readId) : runKey));

        List<PendingReview> reviews = ReviewRegistry.get().all();
        items.add(tokenReportingRow(reviews));
        List<String> waiting = reviews.stream()
                .filter(r -> !r.isSettled())
                .map(r -> r.getJobFullName() + " #" + r.getBuildNumber())
                .sorted()
                .toList();
        long openPrs = reviews.stream().filter(r -> r.isSettled() && r.isPullRequest()).count();
        String detail = waiting.isEmpty()
                ? "Nothing waiting on reviewers in Eyes."
                : waiting.size() + " build" + (waiting.size() == 1 ? "" : "s") + " waiting on reviewers in Eyes,"
                        + " rechecked every minute: " + String.join(", ", waiting) + ".";
        long oldestDays = reviews.stream()
                .mapToLong(r -> (System.currentTimeMillis() - r.getRegisteredAt()) / 86_400_000L)
                .max()
                .orElse(0);
        if (oldestDays >= 1) {
            detail += " Oldest: " + oldestDays + " day" + (oldestDays == 1 ? "" : "s")
                    + " (polled less often as reviews age; dropped after 14 days).";
        }
        if (openPrs > 0) {
            detail += " " + openPrs + " open pull request" + (openPrs == 1 ? " is" : "s are")
                    + " fully reviewed and kept until merged, in case their labels change.";
        }
        items.add(SetupItem.info("Reviews", detail));
        return items;
    }

    private static SetupItem designersRow(ApplitoolsGlobalConfiguration config, String server, String key)
            throws InterruptedException {
        List<String> entries = ApplitoolsGlobalConfiguration.parseDesigners(config.getDesigners());
        if (entries.isEmpty()) {
            return SetupItem.info("Designers", "Any reviewer in Eyes can approve design reviews.");
        }
        if (!Designers.anyTeams(entries)) {
            return SetupItem.info("Designers", "Only " + String.join(", ", entries) + " can approve design reviews.");
        }
        try {
            Designers designers = Designers.resolve(entries, new EyesClient(server, key));
            String who = designers.getAllowed().isEmpty()
                    ? "nobody"
                    : String.join(", ", designers.getAllowed());
            if (designers.getProblem() != null) {
                return SetupItem.warning("Designers", designers.getProblem() + ". Approvers right now: " + who + ".",
                        "Check the team name in Manage Jenkins → System → Applitools Eyes → Designers matches a team"
                                + " in Eyes → Admin → Teams.");
            }
            return SetupItem.ok("Designers", designers.describe() + " → " + who + " can approve design reviews.");
        } catch (IOException e) {
            return SetupItem.warning("Designers", "Couldn't read Eyes teams: " + e.getMessage(),
                    "Team lookups use the read key; make sure it's valid.");
        }
    }

    /** Jobs that aren't multibranch report with githubCredentialsId; show whether GitHub accepts their calls. */
    private static SetupItem tokenReportingRow(List<PendingReview> reviews) {
        long tokenReviews = reviews.stream().filter(r -> r.getGithubCredentialsId() != null).count();
        long lastCall = io.jenkins.plugins.applitools.scm.GitHubRestClient.getLastCallAt();
        String lastError = io.jenkins.plugins.applitools.scm.GitHubRestClient.getLastError();
        if (lastCall == 0) {
            return SetupItem.info("GitHub with a token", tokenReviews == 0
                    ? "No builds have used githubCredentialsId since Jenkins started (only needed for jobs that"
                            + " aren't multibranch)."
                    : "Following " + tokenReviews + " build(s) that report with a token; no GitHub calls yet.");
        }
        long minutes = (System.currentTimeMillis() - lastCall) / 60_000L;
        String when = minutes == 0 ? "less than a minute ago" : minutes + " min ago";
        if (lastError != null) {
            return SetupItem.problem("GitHub with a token", "Last call (" + when + ") failed: " + lastError + ".",
                    "Check the token credential: it needs Commit statuses (write), Pull requests (read) and Contents"
                            + " (read) on the repository.");
        }
        return SetupItem.ok("GitHub with a token", "Last call " + when + " succeeded; following " + tokenReviews
                + " build(s) that report with a token (PRs are polled for labels and merges).");
    }

    private static SetupItem jenkinsUrl() {
        String url = JenkinsLocationConfiguration.get().getUrl();
        if (url == null) {
            return SetupItem.warning("Jenkins URL", "Not set.",
                    "Set it in Manage Jenkins → System → Jenkins Location, so links on PRs point back to Jenkins.");
        }
        if (url.contains("://localhost") || url.contains("://127.0.0.1")) {
            return SetupItem.info("Jenkins URL", url + ": links to Jenkins on PRs only work on this machine."
                    + " Links to Eyes aren't affected.");
        }
        return SetupItem.ok("Jenkins URL", url);
    }

    private static SetupItem keyRow(
            String title, String id, String server, boolean merge, String whyNeeded, String howToAdd)
            throws InterruptedException {
        String key = secret(id);
        if (key == null) {
            return SetupItem.warning(title, "No Secret text credential with ID \"" + id + "\". " + whyNeeded, howToAdd);
        }
        try {
            EyesClient eyes = new EyesClient(server, key);
            return switch (merge ? eyes.checkMerge() : eyes.checkRead()) {
                case ALLOWED -> SetupItem.ok(title, "\"" + id + "\" is valid and allowed to "
                        + (merge ? "merge baselines." : "read results.") + permissions(eyes));
                case NOT_ALLOWED -> SetupItem.problem(title, "Eyes accepts the key in \"" + id + "\" but won't let it "
                        + (merge ? "merge baselines" : "read results") + ".", howToAdd);
                case INVALID -> SetupItem.problem(title, "Eyes doesn't recognize the key in \"" + id + "\".", howToAdd);
            };
        } catch (IOException e) {
            return SetupItem.warning(title, "Couldn't check \"" + id + "\": " + e.getMessage(), null);
        }
    }

    /** " Permissions: read, merge." from Eyes, or "" if it won't say. */
    private static String permissions(EyesClient eyes) throws InterruptedException {
        try {
            List<String> p = eyes.keyInfo().permissions();
            return p.isEmpty() ? "" : " Permissions: " + String.join(", ", p) + ".";
        } catch (IOException e) {
            return "";
        }
    }

    @CheckForNull
    private static String secret(String id) {
        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(id));
        return credentials != null ? Secret.toString(credentials.getSecret()) : null;
    }
}
