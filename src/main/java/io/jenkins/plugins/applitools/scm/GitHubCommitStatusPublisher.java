package io.jenkins.plugins.applitools.scm;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.github_branch_source.Connector;
import org.jenkinsci.plugins.github_branch_source.GitHubAppCredentials;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GitHub;

/**
 * Commit statuses on GitHub, using the job's own scan credentials. Needs the token's {@code repo:status} scope (or a
 * fine-grained token's "Commit statuses: read and write").
 */
@Extension(optional = true)
public class GitHubCommitStatusPublisher extends CommitStatusPublisher {

    @Override
    public boolean handles(@NonNull Run<?, ?> run, @NonNull Mode mode) {
        SCMSource source = SCMSource.SourceByItem.findSource(run.getParent());
        if (!(source instanceof GitHubSCMSource github)) {
            return false;
        }
        if (mode == Mode.STATUSES) {
            return true;
        }
        // AUTO: checks need a GitHub App and the GitHub Checks plugin; otherwise statuses.
        return !checksAvailable() || !(scanCredentials(source, github) instanceof GitHubAppCredentials);
    }

    @Override
    public void publish(
            @NonNull Run<?, ?> run,
            @NonNull String context,
            @NonNull State state,
            @NonNull String description,
            @CheckForNull String targetUrl)
            throws Exception {
        SCMSource source = SCMSource.SourceByItem.findSource(run.getParent());
        if (!(source instanceof GitHubSCMSource github)) {
            return;
        }
        String sha = commit(source, run);
        if (sha == null) {
            throw new IllegalStateException("no commit recorded for " + run.getFullDisplayName());
        }
        GitHub gh = Connector.connect(github.getApiUri(), scanCredentials(source, github));
        try {
            gh.getRepository(github.getRepoOwner() + "/" + github.getRepository())
                    .createCommitStatus(sha, GHCommitState.valueOf(state.name()), targetUrl, description, context);
        } finally {
            Connector.release(gh);
        }
    }

    static boolean checksAvailable() {
        return jenkins.model.Jenkins.get().getPlugin("github-checks") != null;
    }

    /** True if the job connects to GitHub as a GitHub App (so it can post checks). */
    public static boolean usesGitHubApp(SCMSource source) {
        return source instanceof GitHubSCMSource github
                && scanCredentials(source, github) instanceof GitHubAppCredentials;
    }

    private static StandardCredentials scanCredentials(SCMSource source, GitHubSCMSource github) {
        return Connector.lookupScanCredentials(
                source.getOwner(), github.getApiUri(), github.getCredentialsId(), github.getRepoOwner());
    }

    /** The PR's head commit (never a merge commit), or the branch's commit. */
    @CheckForNull
    private static String commit(SCMSource source, Run<?, ?> run) {
        SCMRevision revision = SCMRevisionAction.getRevision(source, run);
        if (revision instanceof PullRequestSCMRevision pr) {
            return pr.getPullHash();
        }
        if (revision instanceof AbstractGitSCMSource.SCMRevisionImpl branch) {
            return branch.getHash();
        }
        return null;
    }
}
