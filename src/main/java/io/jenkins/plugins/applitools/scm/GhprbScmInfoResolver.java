package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Run;

/**
 * Jobs triggered by the GitHub Pull Request Builder (ghprb) plugin, which sets {@code ghprb*} variables instead of the
 * multibranch {@code CHANGE_*} ones. Reads only environment variables, so it needs no plugin dependency.
 */
@Extension(ordinal = 50)
public class GhprbScmInfoResolver extends ScmInfoResolver {

    @Override
    public ScmInfo resolve(@NonNull Run<?, ?> run, @NonNull EnvVars env) {
        String pullId = Util.fixEmptyAndTrim(env.get("ghprbPullId"));
        if (pullId == null) {
            return null;
        }
        String owner = null;
        String repository = null;
        String slug = Util.fixEmptyAndTrim(env.get("ghprbGhRepository"));
        if (slug != null && slug.contains("/")) {
            owner = slug.substring(0, slug.indexOf('/'));
            repository = slug.substring(slug.indexOf('/') + 1);
        }
        return new ScmInfo(
                owner,
                repository,
                Util.fixEmptyAndTrim(env.get("ghprbSourceBranch")),
                Util.fixEmptyAndTrim(env.get("ghprbTargetBranch")),
                Util.fixEmptyAndTrim(env.get("ghprbActualCommit")), // the PR head, not a merge commit
                pullId,
                Util.fixEmptyAndTrim(env.get("ghprbPullTitle")));
    }
}
