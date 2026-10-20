package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;
import java.util.Set;

/** Reads a pull request's labels from the SCM host. */
public abstract class PullRequestLabels implements ExtensionPoint {

    /** @return the labels, or {@code null} if this implementation doesn't handle the build's SCM */
    @CheckForNull
    public abstract Set<String> labels(@NonNull Run<?, ?> run, @NonNull ScmInfo scm) throws Exception;

    /** Labels from the first implementation that knows the build's SCM; empty if none does or the lookup fails. */
    @NonNull
    public static Set<String> of(@NonNull Run<?, ?> run, @NonNull ScmInfo scm) throws Exception {
        if (!scm.isPullRequest()) {
            return Set.of();
        }
        for (PullRequestLabels impl : ExtensionList.lookup(PullRequestLabels.class)) {
            Set<String> labels = impl.labels(run, scm);
            if (labels != null) {
                return labels;
            }
        }
        return Set.of();
    }
}
