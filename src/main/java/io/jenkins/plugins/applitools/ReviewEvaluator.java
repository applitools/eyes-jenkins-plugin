package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.applitools.review.PendingReview;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Brings a tracked build's PR checks up to date with Eyes: re-reads the batch, re-posts "Visual tests" when its verdict
 * changed (e.g. someone accepted the diffs), and re-evaluates "Design review". Used at the end of the build, by
 * {@link ReviewWatcher}, and when a PR label changes.
 */
public final class ReviewEvaluator {

    private ReviewEvaluator() {}

    /** Re-reads Eyes and updates both checks if anything changed. */
    public static void refresh(@NonNull PendingReview review, @NonNull Run<?, ?> run, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        EyesClient reader = reader(review, run);
        if (reader == null) {
            return;
        }
        BatchResults results = reader.getResults(review.getBatchId());
        if (results == null) {
            return;
        }
        review.getExpectations().apply(results);
        String visual = visualState(results);
        if (!visual.equals(review.getLastVisualState())) {
            ApplitoolsResultsAction.addTo(run, results);
            VisualTestsCheck.completed(run, listener, results);
            review.setLastVisualState(visual);
        }
        refreshDesign(review, run, listener, results, reader);
    }

    /** Evaluates and (if changed) posts the Design review check, then marks whether polling can stop. */
    static void refreshDesign(
            @NonNull PendingReview review,
            @NonNull Run<?, ?> run,
            @NonNull TaskListener listener,
            @NonNull BatchResults results,
            @NonNull EyesClient reader)
            throws IOException, InterruptedException {
        DesignReview.Verdict verdict = DesignReview.evaluate(
                review.isDesignReviewRequired(),
                review.getLabel(),
                results,
                review.getChangedTestIds(),
                approversIfNeeded(review, results, reader),
                designers(review, reader));
        String design = verdict.state() + ": " + verdict.title();
        if (review.isDesignCheck() && !design.equals(review.getLastDesignState())) {
            DesignReview.publish(run, listener, verdict, results.getUrl());
            listener.getLogger().println("[Applitools] Design review: " + verdict.title());
            review.setLastDesignState(design);
        }
        review.setSettled(results.getOutcome() != BatchResults.Outcome.CHANGES
                && verdict.state() != DesignReview.State.WAITING);
    }

    /** Expands {@code team:} entries only when a review actually needs them. */
    private static Designers designers(PendingReview review, EyesClient reader)
            throws IOException, InterruptedException {
        if (!review.isDesignReviewRequired() || !Designers.anyTeams(review.getDesigners())) {
            return Designers.of(review.getDesigners());
        }
        return Designers.resolve(review.getDesigners(), reader);
    }

    static String visualState(BatchResults results) {
        return results.getOutcome() + ": " + results.getSummary();
    }

    /** Session-level lookups are only worth it once all changes are resolved and identity matters. */
    private static Map<String, Set<String>> approversIfNeeded(
            PendingReview review, BatchResults results, EyesClient reader) throws IOException, InterruptedException {
        Map<String, Set<String>> approvers = new HashMap<>();
        String eyesBatchId = results.getEyesBatchId();
        if (!review.isDesignReviewRequired()
                || review.getDesigners().isEmpty()
                || results.getUnresolved() > 0
                || results.getFailed() > 0
                || eyesBatchId == null) {
            return approvers;
        }
        for (String testId : review.getChangedTestIds()) {
            approvers.put(testId, reader.sessionApprovers(eyesBatchId, testId));
        }
        return approvers;
    }

    /** Read-key client, falling back to the run key; {@code null} if neither credential exists any more. */
    @CheckForNull
    static EyesClient reader(@NonNull PendingReview review, @NonNull Run<?, ?> run) {
        String key = ApplitoolsBuildWrapper.lookupApiKey(run, review.getReadCredentialsId());
        if (key == null) {
            key = ApplitoolsBuildWrapper.lookupApiKey(run, review.getRunCredentialsId());
        }
        return key != null ? new EyesClient(review.getServerUrl(), key) : null;
    }
}
