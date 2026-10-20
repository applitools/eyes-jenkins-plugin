package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;

/**
 * GitHub multibranch jobs. Reads owner/repo from the job's {@link GitHubSCMSource} and the commit from the build's
 * {@link SCMRevision}, so PR builds get the PR's real head SHA even when Jenkins builds a merge with the target branch.
 * Only loaded when GitHub Branch Source is installed.
 */
@Extension(optional = true, ordinal = 100)
public class GitHubScmInfoResolver extends ScmInfoResolver {

    @Override
    public ScmInfo resolve(@NonNull Run<?, ?> run, @NonNull EnvVars env) {
        SCMSource source = SCMSource.SourceByItem.findSource(run.getParent());
        if (!(source instanceof GitHubSCMSource github)) {
            return null;
        }
        ScmInfo fromEnv = EnvScmInfoResolver.fromEnv(env);
        String sha = fromEnv.commitSha();
        SCMRevision revision = SCMRevisionAction.getRevision(source, run);
        if (revision instanceof PullRequestSCMRevision pr) {
            sha = pr.getPullHash();
        } else if (revision instanceof AbstractGitSCMSource.SCMRevisionImpl branch) {
            sha = branch.getHash();
        }
        return new ScmInfo(
                github.getRepoOwner(),
                github.getRepository(),
                fromEnv.branch(),
                fromEnv.targetBranch(),
                sha,
                fromEnv.changeId(),
                fromEnv.changeTitle());
    }
}
