package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Run;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback that reads the standard variables Jenkins sets: {@code CHANGE_*} / {@code BRANCH_NAME} from multibranch
 * jobs and {@code GIT_*} from the Git plugin. Works for any host, but on PR builds {@code GIT_COMMIT} may be a merge
 * commit, which is why SCM-specific resolvers run first.
 */
@Extension(ordinal = -100)
public class EnvScmInfoResolver extends ScmInfoResolver {

    // https://host/owner/repo(.git), ssh://git@host/owner/repo.git, git@host:owner/repo.git
    private static final Pattern REMOTE = Pattern.compile("[/:]([^/:]+)/([^/]+?)(?:\\.git)?/?$");

    @Override
    public ScmInfo resolve(@NonNull Run<?, ?> run, @NonNull EnvVars env) {
        return fromEnv(env);
    }

    @NonNull
    static ScmInfo fromEnv(@NonNull EnvVars env) {
        String owner = null;
        String repository = null;
        String remote = Util.fixEmptyAndTrim(env.get("GIT_URL"));
        if (remote != null) {
            Matcher m = REMOTE.matcher(remote);
            if (m.find()) {
                owner = m.group(1);
                repository = m.group(2);
            }
        }
        String changeId = Util.fixEmpty(env.get("CHANGE_ID"));
        String branch = changeId != null ? Util.fixEmpty(env.get("CHANGE_BRANCH")) : branchName(env);
        return new ScmInfo(
                owner,
                repository,
                branch,
                changeId != null ? Util.fixEmpty(env.get("CHANGE_TARGET")) : null,
                Util.fixEmpty(env.get("GIT_COMMIT")),
                changeId,
                Util.fixEmpty(env.get("CHANGE_TITLE")));
    }

    @CheckForNull
    private static String branchName(EnvVars env) {
        String branch = Util.fixEmpty(env.get("BRANCH_NAME"));
        if (branch != null) {
            return branch;
        }
        String gitBranch = Util.fixEmpty(env.get("GIT_BRANCH"));
        return gitBranch != null ? gitBranch.replaceFirst("^(refs/heads/|origin/)", "") : null;
    }
}
