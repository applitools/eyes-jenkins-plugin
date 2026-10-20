package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;

/**
 * Works out {@link ScmInfo} for a build. Implementations are tried in ordinal order (highest first) and the first
 * non-null answer wins, so SCM-specific resolvers (GitHub, later GitLab/Bitbucket) take precedence over the generic
 * environment-variable fallback.
 */
public abstract class ScmInfoResolver implements ExtensionPoint {

    /** @return what this resolver knows, or {@code null} if the build isn't from an SCM it understands */
    @CheckForNull
    public abstract ScmInfo resolve(@NonNull Run<?, ?> run, @NonNull EnvVars env);

    @NonNull
    public static ScmInfo resolveFor(@NonNull Run<?, ?> run, @NonNull EnvVars env) {
        for (ScmInfoResolver resolver : ExtensionList.lookup(ScmInfoResolver.class)) {
            ScmInfo info = resolver.resolve(run, env);
            if (info != null) {
                return info;
            }
        }
        return ScmInfo.NONE;
    }
}
