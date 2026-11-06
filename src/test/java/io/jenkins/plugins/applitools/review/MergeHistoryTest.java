package io.jenkins.plugins.applitools.review;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class MergeHistoryTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(MergeHistory.class.getName() + ".maxEntries");
    }

    private static MergeHistory.Entry entry(String pr, String target, MergeHistory.Outcome outcome, long time) {
        return new MergeHistory.Entry("acme/web", pr, "acme/web/feature-" + pr, target, outcome, "title " + pr,
                "Resolve them in **Compare & merge** (`a` → `b`).", time, "job/web/" + pr + "/", "web #" + pr);
    }

    @Test
    void keepsTheNewestAndSurvivesARestart(JenkinsRule j) {
        System.setProperty(MergeHistory.class.getName() + ".maxEntries", "3");
        MergeHistory history = MergeHistory.get();
        for (int i = 1; i <= 5; i++) {
            history.add(entry(String.valueOf(i), "acme/web/main", MergeHistory.Outcome.MERGED, i));
        }

        List<String> prs = history.recent().stream().map(MergeHistory.Entry::getPullRequest).toList();
        assertThat(prs, contains("5", "4", "3"));
        // A fresh instance reads the same entries back from $JENKINS_HOME.
        List<String> reloaded = new MergeHistory().recent().stream().map(MergeHistory.Entry::getPullRequest).toList();
        assertThat(reloaded, contains("5", "4", "3"));
        assertThat(new MergeHistory().recent().get(0).getOutcome(), is(MergeHistory.Outcome.MERGED));
    }

    @Test
    void findsMergesIntoABranchSinceATime(JenkinsRule j) {
        MergeHistory history = MergeHistory.get();
        history.add(entry("1", "acme/web/main", MergeHistory.Outcome.MERGED, 100));
        history.add(entry("2", "acme/web/release", MergeHistory.Outcome.CONFLICTS, 200));
        history.add(entry("3", "Acme/Web/main", MergeHistory.Outcome.CONFLICTS, 300));
        history.add(entry("4", null, MergeHistory.Outcome.SKIPPED, 400));

        List<String> intoMain = history.into("acme/web/main", 150).stream()
                .map(MergeHistory.Entry::getPullRequest)
                .toList();
        assertThat(intoMain, contains("3")); // case-insensitive; #1 is older, #2 is another branch
        assertThat(history.latestFor("ACME/web", "2").getTarget(), is("acme/web/release"));
        assertThat(history.latestFor("acme/web", "9"), nullValue());
        assertThat(history.recent().get(0).getPlainSummary(), is("Resolve them in Compare & merge (a → b)."));
        assertThat(history.recent().get(0).getPullRequestName(), is("acme/web#4"));
    }
}
