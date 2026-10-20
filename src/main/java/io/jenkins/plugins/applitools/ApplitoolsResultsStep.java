package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Run;
import java.io.Serial;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** {@code applitoolsResults()}: the Eyes results recorded on this build, for custom notifications or logic. */
public class ApplitoolsResultsStep extends Step {

    private String batchId;

    @DataBoundConstructor
    public ApplitoolsResultsStep() {}

    public String getBatchId() {
        return batchId;
    }

    /** Which batch, if the build ran several {@code applitools} blocks; defaults to the most recent. */
    @DataBoundSetter
    public void setBatchId(String batchId) {
        this.batchId = Util.fixEmptyAndTrim(batchId);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, batchId);
    }

    private static final class Execution extends SynchronousStepExecution<Map<String, Object>> {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String batchId;

        Execution(StepContext context, String batchId) {
            super(context);
            this.batchId = batchId;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            ApplitoolsResultsAction action = getContext().get(Run.class).getAction(ApplitoolsResultsAction.class);
            if (action == null) {
                return null;
            }
            BatchResults results = batchId != null ? action.find(batchId) : action.getLatest();
            return results != null ? results.toMap() : null;
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "applitoolsResults";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Get Applitools Eyes results";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class);
        }
    }
}
