package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksOutput;
import io.jenkins.plugins.checks.api.ChecksStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The "Applitools / Design review" gate (Chromatic's "UI Review"). When a PR is flagged for design review, it passes
 * only once every visual change in the build has been accepted in Eyes, by someone on the designers list if there is
 * one. When it isn't flagged, the check is posted as skipped, so it can still be a required check.
 */
final class DesignReview {

    static final String CHECK_NAME = "Applitools / Design review";

    enum State {
        NOT_REQUESTED,
        WAITING,
        APPROVED,
        CHANGES_REQUESTED
    }

    record Verdict(@NonNull State state, @NonNull String title, @NonNull String detail) {}

    private DesignReview() {}

    /**
     * @param changedTestIds tests that had visual changes when the build ran
     * @param approvers for each changed test that's now accepted, who accepted it (only needed with designers)
     * @param designers who may approve; empty means anyone reviewing in Eyes
     */
    @NonNull
    static Verdict evaluate(
            boolean required,
            @NonNull String label,
            @NonNull BatchResults results,
            @NonNull Collection<String> changedTestIds,
            @NonNull Map<String, Set<String>> approvers,
            @NonNull Collection<String> designers) {
        if (!required) {
            return new Verdict(
                    State.NOT_REQUESTED,
                    "Not requested",
                    "Add the `" + label + "` label to this pull request to require a designer's approval.");
        }
        if (results.getFailed() > 0) {
            boolean rejected = results.getTests().stream()
                    .anyMatch(t -> changedTestIds.contains(t.getId()) && "failed".equalsIgnoreCase(t.getStatus()));
            return rejected
                    ? new Verdict(State.CHANGES_REQUESTED, "Changes requested",
                            "A reviewer rejected visual changes in Eyes. Push a fix to run the tests again.")
                    : new Verdict(State.CHANGES_REQUESTED, "Visual tests failed",
                            results.getSummary() + ". Fix the tests before design review.");
        }
        if (results.getUnresolved() > 0) {
            return new Verdict(
                    State.WAITING,
                    "Waiting for design review",
                    results.getUnresolved() + " visual change" + (results.getUnresolved() == 1 ? "" : "s")
                            + " to review in Eyes" + (designers.isEmpty() ? "." : " by a designer."));
        }
        if (changedTestIds.isEmpty()) {
            return new Verdict(State.APPROVED, "No visual changes", "Nothing in this build needs design review.");
        }
        if (designers.isEmpty()) {
            return new Verdict(
                    State.APPROVED,
                    "Approved",
                    changedTestIds.size() + " change" + (changedTestIds.size() == 1 ? "" : "s") + " accepted in Eyes.");
        }

        Set<String> allowed = designers.stream().map(DesignReview::normalize).collect(Collectors.toSet());
        Set<String> approvedBy = new TreeSet<>();
        List<String> notByDesigner = new ArrayList<>();
        for (String testId : changedTestIds) {
            Set<String> who = approvers.getOrDefault(testId, Set.of());
            List<String> designerApprovals = who.stream()
                    .filter(u -> allowed.contains(normalize(u)))
                    .toList();
            if (designerApprovals.isEmpty()) {
                notByDesigner.addAll(who);
            } else {
                approvedBy.addAll(designerApprovals);
            }
        }
        if (approvedBy.isEmpty() || !notByDesigner.isEmpty()) {
            Set<String> others = new TreeSet<>(notByDesigner);
            return new Verdict(
                    State.WAITING,
                    "Waiting for a designer",
                    "Changes were accepted"
                            + (others.isEmpty() ? "" : " by " + String.join(", ", others))
                            + ", but a designer (" + String.join(", ", designers) + ") must approve them.");
        }
        return new Verdict(State.APPROVED, "Approved by " + String.join(", ", approvedBy),
                "Every visual change was accepted by a designer.");
    }

    private static String normalize(String user) {
        return user.trim().toLowerCase(Locale.ROOT);
    }

    static void publish(
            @NonNull Run<?, ?> run, @NonNull TaskListener listener, @NonNull Verdict verdict, String batchUrl) {
        ChecksDetails.ChecksDetailsBuilder details = new ChecksDetails.ChecksDetailsBuilder().withName(CHECK_NAME);
        switch (verdict.state()) {
            case WAITING -> details.withStatus(ChecksStatus.IN_PROGRESS);
            case NOT_REQUESTED -> details.withStatus(ChecksStatus.COMPLETED)
                    .withConclusion(ChecksConclusion.SKIPPED)
                    .withCompletedAt(VisualTestsCheck.now());
            case APPROVED -> details.withStatus(ChecksStatus.COMPLETED)
                    .withConclusion(ChecksConclusion.SUCCESS)
                    .withCompletedAt(VisualTestsCheck.now());
            case CHANGES_REQUESTED -> details.withStatus(ChecksStatus.COMPLETED)
                    .withConclusion(ChecksConclusion.FAILURE)
                    .withCompletedAt(VisualTestsCheck.now());
        }
        String summary = (batchUrl != null ? "**[Review in Eyes](" + batchUrl + ")**\n\n" : "") + verdict.detail();
        details.withOutput(new ChecksOutput.ChecksOutputBuilder()
                .withTitle(verdict.title())
                .withSummary(summary)
                .build());
        if (batchUrl != null) {
            details.withDetailsURL(batchUrl);
        }
        VisualTestsCheck.publish(run, listener, details.build());
    }
}
