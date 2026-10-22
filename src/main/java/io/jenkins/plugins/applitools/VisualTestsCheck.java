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
import io.jenkins.plugins.applitools.scm.CommitStatusPublisher;
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
    /**
     * Test names, batch names and environments come from test code, which a PR author controls: show them as plain
     * text, not Markdown (no links, images or formatting), on one table row.
     */
    static String escape(@CheckForNull String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.replace('\r', ' ').replace('\n', ' ').toCharArray()) {
            if ("\\`*_[]<>!|~&#".indexOf(c) >= 0) { // what makes links, images, formatting or HTML
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Posts a result as a check, or as a commit status when the job can't post checks (e.g. a GitHub job using a token
     * rather than a GitHub App). Problems are logged, never thrown: reporting must not fail the build.
     */
    static void publish(Run<?, ?> run, TaskListener listener, ChecksDetails details) {
        String name = details.getName().orElse(NAME);
        try {
            CommitStatusPublisher statuses =
                    CommitStatusPublisher.forRun(run, ApplitoolsGlobalConfiguration.get().getGithubReporting());
            if (statuses != null) {
                Status status = toStatus(details);
                statuses.publish(run, name, status.state(), status.description(),
                        details.getDetailsURL().orElse(null));
                return;
            }
            ChecksPublisherFactory.fromRun(run, listener).publish(details);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            listener.getLogger().println(LOG_PREFIX + "WARNING: could not publish '" + name + "': " + e);
        }
    }

    record Status(CommitStatusPublisher.State state, String description) {}

    /**
     * Commit statuses have four states and a 140-character description. Anything that should block a merge until a
     * human acts (in progress, action required) is pending; skipped and neutral pass.
     */
    @NonNull
    static Status toStatus(@NonNull ChecksDetails details) {
        CommitStatusPublisher.State state;
        if (details.getStatus() != ChecksStatus.COMPLETED) {
            state = CommitStatusPublisher.State.PENDING;
        } else {
            state = switch (details.getConclusion()) {
                case SUCCESS, NEUTRAL, SKIPPED -> CommitStatusPublisher.State.SUCCESS;
                case FAILURE, TIME_OUT, CANCELED -> CommitStatusPublisher.State.FAILURE;
                case ACTION_REQUIRED, NONE -> CommitStatusPublisher.State.PENDING;
            };
        }
        String title = details.getOutput().flatMap(ChecksOutput::getTitle).orElse("");
        String description = title.length() > 140 ? title.substring(0, 139) + "…" : title;
        return new Status(state, description);
    }
}
