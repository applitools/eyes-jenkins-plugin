package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import java.io.IOException;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Build step usable from Freestyle jobs (Add build step) and Pipeline ({@code applitools appName: '...'}).
 *
 * <p>Placeholder behaviour: logs what it would do. The real Eyes integration gets designed next.
 */
public class ApplitoolsBuilder extends Builder implements SimpleBuildStep {

    private final String appName;
    private String batchName;

    /** Required fields go in the constructor; Jenkins binds form/Pipeline args to it by parameter name. */
    @DataBoundConstructor
    public ApplitoolsBuilder(String appName) {
        this.appName = Util.fixEmptyAndTrim(appName);
    }

    public String getAppName() {
        return appName;
    }

    public String getBatchName() {
        return batchName;
    }

    /** Optional fields use setters so Pipeline scripts can omit them. */
    @DataBoundSetter
    public void setBatchName(String batchName) {
        this.batchName = Util.fixEmptyAndTrim(batchName);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        String serverUrl = ApplitoolsGlobalConfiguration.get().getServerUrl();
        String batch = batchName != null ? env.expand(batchName) : run.getFullDisplayName();
        listener.getLogger().printf("[Applitools] app=%s batch=%s server=%s%n", appName, batch, serverUrl);
    }

    @Symbol("applitools")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Applitools Eyes";
        }

        /** Live form validation; Stapler wires {@code doCheck<Field>} to the matching field in config.jelly. */
        @POST
        public FormValidation doCheckAppName(@QueryParameter String value) {
            return Util.fixEmptyAndTrim(value) == null
                    ? FormValidation.error("App name is required")
                    : FormValidation.ok();
        }
    }
}
