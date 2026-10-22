package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;

/**
 * Posts results as commit statuses, for jobs that can't post checks (e.g. GitHub jobs using a personal access token:
 * the Checks API only accepts GitHub Apps).
 */
public abstract class CommitStatusPublisher implements ExtensionPoint {

    /** How GitHub results are delivered; set in Manage Jenkins → System → Applitools Eyes. */
    public enum Mode {
        /** Checks when the job uses a GitHub App, commit statuses otherwise. */
        AUTO("Automatic: checks with a GitHub App, commit statuses with a token"),
        CHECKS("Always checks"),
        STATUSES("Always commit statuses");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        /** Shown in Manage Jenkins → System. */
        public String getDisplayName() {
            return displayName;
        }
    }

    /** Commit status state names, as the SCM host's API spells them. */
    public enum State {
        PENDING,
        SUCCESS,
        FAILURE,
        ERROR
    }

    /**
     * @return whether this implementation should post statuses for the run under the given mode (false lets the caller
     *     post checks instead)
     */
    public abstract boolean handles(@NonNull Run<?, ?> run, @NonNull Mode mode);

    public abstract void publish(
            @NonNull Run<?, ?> run,
            @NonNull String context,
            @NonNull State state,
            @NonNull String description,
            @CheckForNull String targetUrl)
            throws Exception;

    /** The implementation that wants this run, or {@code null} to use checks. */
    @CheckForNull
    public static CommitStatusPublisher forRun(@NonNull Run<?, ?> run, @NonNull Mode mode) {
        if (mode == Mode.CHECKS) {
            return null;
        }
        for (CommitStatusPublisher publisher : ExtensionList.lookup(CommitStatusPublisher.class)) {
            if (publisher.handles(run, mode)) {
                return publisher;
            }
        }
        return null;
    }
}
