package io.jenkins.plugins.applitools.scm;

import hudson.EnvVars;

/** Exposes the package-private env parser to tests in other packages. */
public final class EnvScmInfoResolverTestAccess {

    private EnvScmInfoResolverTestAccess() {}

    public static ScmInfo fromEnv(EnvVars env) {
        return EnvScmInfoResolver.fromEnv(env);
    }
}
