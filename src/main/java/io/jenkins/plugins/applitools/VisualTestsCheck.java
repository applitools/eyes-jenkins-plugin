package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksPublisherFactory;
import io.jenkins.plugins.checks.api.ChecksStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * The "Applitools / Visual tests" check on the commit (Chromatic's "UI Tests"). Passes only when every test passed or
 * created a new baseline; unreviewed changes are "action required" and link straight to the batch in Eyes, so branch
 * protection blocks the merge until someone reviews them.
 *
 * <p>Delivery goes through the Checks API, so it lands on GitHub when GitHub Checks is installed and the job uses a
 * GitHub App credential, and is a no-op otherwise.
 */
final class VisualTestsCheck {

    static final String NAME = "Applitools / Visual tests";
    private static final String LOG_PREFIX = "[Applitools] ";

    private VisualTestsCheck() {}

    static void started(@NonNull Run<?, ?> run, @NonNull TaskListener listener, @NonNull String batchName) {
        publish(run, listener, new ChecksDetails.ChecksDetailsBuilder()
                .withName(NAME)
                .withStatus(ChecksStatus.IN_PROGRESS)
                .withStartedAt(now())
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle("Running visual tests")
                        .withSummary("Batch: " + batchName)
                        .build())
                .build());
    }

    static void completed(@NonNull Run<?, ?> run, @NonNull TaskListener listener, @NonNull BatchResults results) {
        ChecksConclusion conclusion = switch (results.getOutcome()) {
            case PASSED -> ChecksConclusion.SUCCESS;
            case CHANGES -> ChecksConclusion.ACTION_REQUIRED;
            case FAILED, NO_RESULTS -> ChecksConclusion.FAILURE;
        };
        String title = switch (results.getOutcome()) {
            case PASSED -> results.getSummary();
            case CHANGES -> results.getUnresolved() + " visual change" + (results.getUnresolved() == 1 ? "" : "s")
                    + " to review";
            case FAILED -> results.getSummary();
            case NO_RESULTS -> "No Eyes results";
        };
        ChecksDetails.ChecksDetailsBuilder details = new ChecksDetails.ChecksDetailsBuilder()
                .withName(NAME)
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(conclusion)
                .withCompletedAt(now())
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle(title)
                        .withSummary(summary(results))
                        .build());
        if (results.getUrl() != null) {
            details.withDetailsURL(results.getUrl());
        }
        publish(run, listener, details.build());
    }

    /** Results couldn't be collected; {@code blocking} decides whether the check fails or stays neutral. */
    static void unavailable(
            @NonNull Run<?, ?> run, @NonNull TaskListener listener, @NonNull String reason, boolean blocking) {
        publish(run, listener, new ChecksDetails.ChecksDetailsBuilder()
                .withName(NAME)
                .withStatus(ChecksStatus.COMPLETED)
                .withConclusion(blocking ? ChecksConclusion.FAILURE : ChecksConclusion.NEUTRAL)
                .withCompletedAt(now())
                .withOutput(new ChecksOutput.ChecksOutputBuilder()
                        .withTitle(blocking ? "Couldn't read Eyes results" : "Results not collected")
                        .withSummary(reason + "\n\nSee the Jenkins build log for details.")
                        .build())
                .build());
    }

    @NonNull
    static String summary(@NonNull BatchResults results) {
        StringBuilder md = new StringBuilder();
        if (results.getUrl() != null) {
            md.append("**[Review in Eyes](").append(results.getUrl()).append(")**");
            if (results.getName() != null) {
                md.append(" · ").append(escape(results.getName()));
            }
            md.append("\n\n");
        }
        md.append(results.getSummary()).append("\n");
        if (!results.getTests().isEmpty()) {
            md.append("\n| Test | Browser / device | Status |\n| --- | --- | --- |\n");
            for (BatchResults.Test test : results.getTests()) {
                String name = escape(test.getName());
                md.append("| ")
                        .append(test.getUrl() != null ? "[" + name + "](" + test.getUrl() + ")" : name)
                        .append(" | ")
                        .append(escape(test.getEnvironment()))
                        .append(" | ")
                        .append(statusBadge(test.getStatus()))
                        .append(" |\n");
            }
        }
        return md.toString();
    }

    private static String statusBadge(@CheckForNull String status) {
        String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "passed", "new" -> "✅ " + s;
            case "unresolved" -> "⚠️ unresolved";
            case "failed" -> "❌ failed";
            default -> s;
        };
    }

    /** Keeps names from breaking the markdown table. */
    private static String escape(@CheckForNull String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void publish(Run<?, ?> run, TaskListener listener, ChecksDetails details) {
        try {
            ChecksPublisherFactory.fromRun(run, listener).publish(details);
        } catch (RuntimeException e) {
            // A check that fails to post must never fail the build.
            listener.getLogger().println(LOG_PREFIX + "WARNING: could not publish the '" + NAME + "' check: " + e);
        }
    }
}
