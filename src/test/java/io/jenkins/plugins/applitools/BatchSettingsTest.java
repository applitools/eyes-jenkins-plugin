package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import hudson.EnvVars;
import io.jenkins.plugins.applitools.scm.EnvScmInfoResolverTestAccess;
import io.jenkins.plugins.applitools.scm.ScmInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BatchSettingsTest {

    private static final String SHA = "599f99f5e491853c3e4aabe3487569a66d92b231";

    @Test
    void pullRequest() {
        ScmInfo scm = new ScmInfo("timhosey", "web", "test-fire", "main", SHA, "2", "Add readme");

        BatchSettings batch = BatchSettings.compute(scm, "web/PR-2", 7, BatchSettings.Overrides.NONE);

        assertThat(batch.batchId(), is(SHA));
        assertThat(batch.batchName(), is("timhosey/web PR #2: Add readme"));
        assertThat(batch.branchName(), is("timhosey/web/test-fire"));
        assertThat(batch.parentBranchName(), is("timhosey/web/main"));
        assertThat(batch.sequenceName(), is("timhosey/web"));
    }

    @Test
    void branchBuild() {
        ScmInfo scm = new ScmInfo("timhosey", "web", "main", null, SHA, null, null);

        BatchSettings batch = BatchSettings.compute(scm, "web/main", 7, BatchSettings.Overrides.NONE);

        // Not the PR build's batch: one commit is often built both ways at once.
        assertThat(batch.batchId(), is(SHA + "-main"));
        assertThat(batch.batchName(), is("timhosey/web main"));
        assertThat(batch.branchName(), is("timhosey/web/main"));
        assertThat(batch.parentBranchName(), nullValue());
    }

    @Test
    void unknownRepoFallsBackToJobName() {
        ScmInfo scm = new ScmInfo(null, null, "main", null, SHA, null, null);

        BatchSettings batch = BatchSettings.compute(scm, "folder/web", 7, BatchSettings.Overrides.NONE);

        assertThat(batch.batchName(), is("folder/web main"));
        assertThat(batch.branchName(), is("main"));
    }

    @Test
    void overridesWin() {
        ScmInfo scm = new ScmInfo("timhosey", "web", "test-fire", "main", SHA, "2", "Add readme");

        BatchSettings batch = BatchSettings.compute(
                scm, "web/PR-2", 7, new BatchSettings.Overrides("id", "Name", "b", "p"));

        assertThat(batch.batchId(), is("id"));
        assertThat(batch.batchName(), is("Name"));
        assertThat(batch.branchName(), is("b"));
        assertThat(batch.parentBranchName(), is("p"));
    }

    @Test
    void envResolverReadsMultibranchPullRequest() {
        ScmInfo scm = EnvScmInfoResolverTestAccess.fromEnv(new EnvVars(Map.of(
                "GIT_URL", "https://github.com/timhosey/applitools-jenkins-plugin-test.git",
                "GIT_COMMIT", SHA,
                "BRANCH_NAME", "PR-2",
                "CHANGE_ID", "2",
                "CHANGE_BRANCH", "test-fire",
                "CHANGE_TARGET", "main",
                "CHANGE_TITLE", "Add readme")));

        assertThat(scm, is(new ScmInfo(
                "timhosey", "applitools-jenkins-plugin-test", "test-fire", "main", SHA, "2", "Add readme")));
    }

    @Test
    void envResolverParsesSshRemoteAndGitBranch() {
        ScmInfo scm = EnvScmInfoResolverTestAccess.fromEnv(new EnvVars(Map.of(
                "GIT_URL", "git@github.com:acme/web.git", "GIT_BRANCH", "origin/feature/x")));

        assertThat(scm.repoSlug(), is("acme/web"));
        assertThat(scm.branch(), is("feature/x"));
        assertThat(scm.isPullRequest(), is(false));
    }
}
