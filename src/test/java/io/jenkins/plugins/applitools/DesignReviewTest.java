package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import io.jenkins.plugins.applitools.DesignReview.State;
import io.jenkins.plugins.applitools.DesignReview.Verdict;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesignReviewTest {

    private static final List<String> NO_DESIGNERS = List.of();
    private static final List<String> DESIGNERS = List.of("Dana@Example.com");

    private static BatchResults results(String... statuses) {
        List<BatchResults.Test> tests = new java.util.ArrayList<>();
        int passed = 0, failed = 0, unresolved = 0;
        for (int i = 0; i < statuses.length; i++) {
            tests.add(new BatchResults.Test("t" + i, "test " + i, "Chrome", statuses[i], null));
            switch (statuses[i]) {
                case "failed" -> failed++;
                case "unresolved" -> unresolved++;
                default -> passed++;
            }
        }
        return new BatchResults("b", "batch", "https://eyes/b", passed, failed, unresolved, 0, 0, tests.size(), tests);
    }

    private static Verdict eval(
            boolean required, BatchResults r, Set<String> changed, Map<String, Set<String>> approvers,
            List<String> designers) {
        return DesignReview.evaluate(required, "design-review", r, changed, approvers, Designers.of(designers));
    }

    @Test
    void notRequestedIsSkippedWithHowToRequest() {
        Verdict v = eval(false, results("unresolved"), Set.of("t0"), Map.of(), NO_DESIGNERS);

        assertThat(v.state(), is(State.NOT_REQUESTED));
        assertThat(v.detail(), containsString("`design-review` label"));
    }

    @Test
    void unresolvedChangesWait() {
        Verdict v = eval(true, results("passed", "unresolved"), Set.of("t1"), Map.of(), DESIGNERS);

        assertThat(v.state(), is(State.WAITING));
        assertThat(v.detail(), is("1 visual change to review in Eyes by a designer."));
    }

    @Test
    void rejectedChangeRequestsChanges() {
        Verdict v = eval(true, results("failed", "passed"), Set.of("t0"), Map.of(), NO_DESIGNERS);

        assertThat(v.state(), is(State.CHANGES_REQUESTED));
        assertThat(v.title(), is("Changes requested"));
    }

    @Test
    void failingTestsThatWerentChangesAreReportedAsFailures() {
        Verdict v = eval(true, results("failed"), Set.of(), Map.of(), NO_DESIGNERS);

        assertThat(v.title(), is("Visual tests failed"));
    }

    @Test
    void noChangesNeedNoReview() {
        assertThat(eval(true, results("passed"), Set.of(), Map.of(), DESIGNERS).state(), is(State.APPROVED));
    }

    @Test
    void anyReviewerApprovesWithoutDesignersList() {
        Verdict v = eval(true, results("passed", "passed"), Set.of("t0", "t1"), Map.of(), NO_DESIGNERS);

        assertThat(v.state(), is(State.APPROVED));
        assertThat(v.detail(), is("2 changes accepted in Eyes."));
    }

    @Test
    void designerApprovalCaseInsensitive() {
        Verdict v = eval(
                true, results("passed"), Set.of("t0"), Map.of("t0", Set.of("dana@example.com")), DESIGNERS);

        assertThat(v.state(), is(State.APPROVED));
        assertThat(v.title(), is("Approved by dana@example.com"));
    }

    @Test
    void nonDesignerAcceptanceKeepsWaiting() {
        Verdict v = eval(
                true, results("passed"), Set.of("t0"), Map.of("t0", Set.of("dev@example.com")), DESIGNERS);

        assertThat(v.state(), is(State.WAITING));
        assertThat(v.detail(), containsString("accepted by dev@example.com, but a designer (Dana@Example.com)"));
    }

    @Test
    void everyChangeNeedsADesigner() {
        Verdict v = eval(
                true,
                results("passed", "passed"),
                Set.of("t0", "t1"),
                Map.of("t0", Set.of("dana@example.com"), "t1", Set.of("dev@example.com")),
                DESIGNERS);

        assertThat(v.state(), is(State.WAITING));
    }

    @Test
    void unknownApproverKeepsWaitingWhenDesignersAreRequired() {
        // Accepted but not saved yet, or attribution unavailable: don't let it through.
        Verdict v = eval(true, results("passed"), Set.of("t0"), Map.of(), DESIGNERS);

        assertThat(v.state(), is(State.WAITING));
    }
}
