package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.RunAction2;

/**
 * Eyes results for a build: a summary panel on the build page and a sidebar link straight to the batch in Eyes. Holds
 * one entry per {@code applitools} block (usually just one).
 */
public class ApplitoolsResultsAction implements RunAction2 {

    private final List<BatchResults> batches = new ArrayList<>();
    private transient Run<?, ?> run;

    @NonNull
    static synchronized ApplitoolsResultsAction addTo(@NonNull Run<?, ?> run, @NonNull BatchResults results)
            throws java.io.IOException {
        ApplitoolsResultsAction action = run.getAction(ApplitoolsResultsAction.class);
        if (action == null) {
            action = new ApplitoolsResultsAction();
            run.addAction(action);
        }
        action.batches.removeIf(b -> b.getBatchId().equals(results.getBatchId()));
        action.batches.add(results);
        run.save();
        return action;
    }

    public List<BatchResults> getBatches() {
        return Collections.unmodifiableList(batches);
    }

    @CheckForNull
    public BatchResults getLatest() {
        return batches.isEmpty() ? null : batches.get(batches.size() - 1);
    }

    @CheckForNull
    public BatchResults find(@NonNull String batchId) {
        return batches.stream()
                .filter(b -> b.getBatchId().equals(batchId))
                .findFirst()
                .orElse(null);
    }

    /** Worst outcome across all batches in the build. */
    @NonNull
    public BatchResults.Outcome getOutcome() {
        return batches.stream()
                .map(BatchResults::getOutcome)
                .min(Enum::compareTo)
                .orElse(BatchResults.Outcome.NO_RESULTS);
    }

    public Run<?, ?> getRun() {
        return run;
    }

    @Override
    public String getIconFileName() {
        return getUrlName() != null ? "symbol-eye-outline plugin-ionicons-api" : null;
    }

    @Override
    public String getDisplayName() {
        return "Review in Eyes";
    }

    /** The sidebar link goes straight to the batch in the Eyes dashboard. */
    @Override
    public String getUrlName() {
        BatchResults latest = getLatest();
        return latest != null ? latest.getUrl() : null;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }
}
