package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a build's batch must contain, so a suite that silently ran nothing (e.g. Storybook failing to launch a
 * browser) can't pass the gate on the strength of the other suites.
 */
public final class Expectations implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final Expectations NONE = new Expectations(null, List.of());

    private final Integer minTests;
    private final List<String> apps;

    public Expectations(@CheckForNull Integer minTests, @CheckForNull List<String> apps) {
        this.minTests = minTests != null && minTests > 0 ? minTests : null;
        this.apps = apps != null ? new ArrayList<>(apps) : new ArrayList<>();
    }

    public boolean isEmpty() {
        return minTests == null && apps.isEmpty();
    }

    /** What's missing from these results, or {@code null} if they meet the expectations. */
    @CheckForNull
    public String check(@NonNull BatchResults results) {
        List<String> problems = new ArrayList<>();
        if (minTests != null && results.getTotal() < minTests) {
            problems.add("expected at least " + minTests + " test" + (minTests == 1 ? "" : "s") + ", Eyes received "
                    + results.getTotal());
        }
        if (!apps.isEmpty()) {
            Set<String> seen = results.getTests().stream()
                    .map(BatchResults.Test::getAppName)
                    .filter(a -> a != null)
                    .map(a -> a.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            List<String> missing = apps.stream()
                    .filter(a -> !seen.contains(a.toLowerCase(Locale.ROOT)))
                    .toList();
            if (!missing.isEmpty()) {
                problems.add("no tests from " + String.join(", ", missing));
            }
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    /** Applies the expectations to results (marking them failed if unmet) and returns them. */
    @NonNull
    public BatchResults apply(@NonNull BatchResults results) {
        String problem = check(results);
        if (problem != null) {
            results.failExpectation(problem);
        }
        return results;
    }

    @CheckForNull
    public Integer getMinTests() {
        return minTests;
    }

    public List<String> getApps() {
        return apps;
    }
}
