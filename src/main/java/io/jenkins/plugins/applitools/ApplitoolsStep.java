package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.console.ConsoleLogFilter;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.io.Serial;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.tasks.SimpleBuildWrapper;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * The {@code applitools { }} block in Pipeline. Same options and behavior as {@link ApplitoolsBuildWrapper}, which
 * it runs and which still serves Freestyle jobs. The difference is how the API key is kept: Pipeline's generic wrapper
 * support saves a wrapper's environment variables as plain text in the build's state on disk, while this step keeps
 * the key encrypted there and declares it sensitive, as {@code withCredentials} does. Pipeline then also warns when a
 * script interpolates the key into a Groovy string.
 */
public class ApplitoolsStep extends Step {

    private final ApplitoolsBuildWrapper wrapper;

    @DataBoundConstructor
    public ApplitoolsStep() {
        this(new ApplitoolsBuildWrapper());
    }

    ApplitoolsStep(@NonNull ApplitoolsBuildWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @CheckForNull
    public String getCredentialsId() {
        return wrapper.getCredentialsId();
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        wrapper.setCredentialsId(credentialsId);
    }

    @CheckForNull
    public String getReadCredentialsId() {
        return wrapper.getReadCredentialsId();
    }

    @DataBoundSetter
    public void setReadCredentialsId(String readCredentialsId) {
        wrapper.setReadCredentialsId(readCredentialsId);
    }

    @CheckForNull
    public String getServerUrl() {
        return wrapper.getServerUrl();
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        wrapper.setServerUrl(serverUrl);
    }

    @CheckForNull
    public String getBatchId() {
        return wrapper.getBatchId();
    }

    @DataBoundSetter
    public void setBatchId(String batchId) {
        wrapper.setBatchId(batchId);
    }

    @CheckForNull
    public String getBatchName() {
        return wrapper.getBatchName();
    }

    @DataBoundSetter
    public void setBatchName(String batchName) {
        wrapper.setBatchName(batchName);
    }

    @CheckForNull
    public String getBranchName() {
        return wrapper.getBranchName();
    }

    @DataBoundSetter
    public void setBranchName(String branchName) {
        wrapper.setBranchName(branchName);
    }

    @CheckForNull
    public String getParentBranchName() {
        return wrapper.getParentBranchName();
    }

    @DataBoundSetter
    public void setParentBranchName(String parentBranchName) {
        wrapper.setParentBranchName(parentBranchName);
    }

    public boolean isCloseBatch() {
        return wrapper.isCloseBatch();
    }

    @DataBoundSetter
    public void setCloseBatch(boolean closeBatch) {
        wrapper.setCloseBatch(closeBatch);
    }

    public boolean isWaitForResults() {
        return wrapper.isWaitForResults();
    }

    @DataBoundSetter
    public void setWaitForResults(boolean waitForResults) {
        wrapper.setWaitForResults(waitForResults);
    }

    public int getResultsTimeout() {
        return wrapper.getResultsTimeout();
    }

    @DataBoundSetter
    public void setResultsTimeout(int resultsTimeout) {
        wrapper.setResultsTimeout(resultsTimeout);
    }

    @CheckForNull
    public ChangesPolicy getOnChanges() {
        return wrapper.getOnChanges();
    }

    @DataBoundSetter
    public void setOnChanges(ChangesPolicy onChanges) {
        wrapper.setOnChanges(onChanges);
    }

    public boolean isChecks() {
        return wrapper.isChecks();
    }

    @DataBoundSetter
    public void setChecks(boolean checks) {
        wrapper.setChecks(checks);
    }

    @CheckForNull
    public String getRepo() {
        return wrapper.getRepo();
    }

    @DataBoundSetter
    public void setRepo(String repo) {
        wrapper.setRepo(repo);
    }

    @CheckForNull
    public String getPullRequest() {
        return wrapper.getPullRequest();
    }

    @DataBoundSetter
    public void setPullRequest(String pullRequest) {
        wrapper.setPullRequest(pullRequest);
    }

    @CheckForNull
    public String getCommit() {
        return wrapper.getCommit();
    }

    @DataBoundSetter
    public void setCommit(String commit) {
        wrapper.setCommit(commit);
    }

    @CheckForNull
    public String getBranch() {
        return wrapper.getBranch();
    }

    @DataBoundSetter
    public void setBranch(String branch) {
        wrapper.setBranch(branch);
    }

    @CheckForNull
    public String getTargetBranch() {
        return wrapper.getTargetBranch();
    }

    @DataBoundSetter
    public void setTargetBranch(String targetBranch) {
        wrapper.setTargetBranch(targetBranch);
    }

    @CheckForNull
    public String getGithubCredentialsId() {
        return wrapper.getGithubCredentialsId();
    }

    @DataBoundSetter
    public void setGithubCredentialsId(String githubCredentialsId) {
        wrapper.setGithubCredentialsId(githubCredentialsId);
    }

    @CheckForNull
    public String getGithubApiUrl() {
        return wrapper.getGithubApiUrl();
    }

    @DataBoundSetter
    public void setGithubApiUrl(String githubApiUrl) {
        wrapper.setGithubApiUrl(githubApiUrl);
    }

    @CheckForNull
    public Boolean getMergeBaselines() {
        return wrapper.getMergeBaselines();
    }

    @DataBoundSetter
    public void setMergeBaselines(Boolean mergeBaselines) {
        wrapper.setMergeBaselines(mergeBaselines);
    }

    public boolean isJudgeDiffs() {
        return wrapper.isJudgeDiffs();
    }

    @DataBoundSetter
    public void setJudgeDiffs(boolean judgeDiffs) {
        wrapper.setJudgeDiffs(judgeDiffs);
    }

    @CheckForNull
    public Integer getExpectTests() {
        return wrapper.getExpectTests();
    }

    @DataBoundSetter
    public void setExpectTests(Integer expectTests) {
        wrapper.setExpectTests(expectTests);
    }

    @CheckForNull
    public List<String> getExpectApps() {
        return wrapper.getExpectApps();
    }

    @DataBoundSetter
    public void setExpectApps(List<String> expectApps) {
        wrapper.setExpectApps(expectApps);
    }

    @CheckForNull
    public Boolean getDesignReview() {
        return wrapper.getDesignReview();
    }

    @DataBoundSetter
    public void setDesignReview(Boolean designReview) {
        wrapper.setDesignReview(designReview);
    }

    @CheckForNull
    public String getDesignReviewLabel() {
        return wrapper.getDesignReviewLabel();
    }

    @DataBoundSetter
    public void setDesignReviewLabel(String designReviewLabel) {
        wrapper.setDesignReviewLabel(designReviewLabel);
    }

    @CheckForNull
    public String getDesigners() {
        return wrapper.getDesigners();
    }

    @DataBoundSetter
    public void setDesigners(String designers) {
        wrapper.setDesigners(designers);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(wrapper, context);
    }

    static final class Execution extends AbstractStepExecutionImpl {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Only needed to start the block; not saved with the build. */
        private final transient ApplitoolsBuildWrapper wrapper;

        Execution(ApplitoolsBuildWrapper wrapper, StepContext context) {
            super(context);
            this.wrapper = wrapper;
        }

        @Override
        public boolean start() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            TaskListener listener = context.get(TaskListener.class);
            SimpleBuildWrapper.Context block = wrapper.createContext();
            wrapper.setUp(block, run, listener, context.get(EnvVars.class));

            Map<String, String> plain = new HashMap<>(block.getEnv());
            Map<String, Secret> secrets = new HashMap<>();
            String apiKey = plain.remove(ApplitoolsBuildWrapper.ENV_API_KEY);
            if (apiKey != null) {
                secrets.put(ApplitoolsBuildWrapper.ENV_API_KEY, Secret.fromString(apiKey));
            }
            BodyInvoker body = context.newBodyInvoker()
                    .withContext(EnvironmentExpander.merge(
                            context.get(EnvironmentExpander.class), new Expander(plain, secrets)))
                    .withCallback(new TearDown(block.getDisposer()));
            ConsoleLogFilter mask = wrapper.createLoggerDecorator(run);
            if (mask != null) {
                body.withContext(BodyInvoker.mergeConsoleLogFilters(context.get(ConsoleLogFilter.class), mask));
            }
            body.start();
            return false;
        }
    }

    /** The block's environment, with the API key kept as a {@link Secret} and declared sensitive. */
    static final class Expander extends EnvironmentExpander {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Map<String, String> plain;
        private final Map<String, Secret> secrets;

        Expander(Map<String, String> plain, Map<String, Secret> secrets) {
            this.plain = plain;
            this.secrets = secrets;
        }

        @Override
        public void expand(@NonNull EnvVars env) {
            env.overrideExpandingAll(plain);
            secrets.forEach((name, value) -> env.override(name, value.getPlainText()));
        }

        @NonNull
        @Override
        public Set<String> getSensitiveVariables() {
            return Set.copyOf(secrets.keySet());
        }
    }

    /** Closes the batch, reports results and applies the outcome, whether the body passed or failed. */
    private static final class TearDown extends BodyExecutionCallback.TailCall {
        @Serial
        private static final long serialVersionUID = 1L;

        @CheckForNull
        private final SimpleBuildWrapper.Disposer disposer;

        TearDown(@CheckForNull SimpleBuildWrapper.Disposer disposer) {
            this.disposer = disposer;
        }

        @Override
        protected void finished(StepContext context) throws IOException, InterruptedException {
            if (disposer != null) {
                disposer.tearDown(context.get(Run.class), context.get(TaskListener.class));
            }
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        private static ApplitoolsBuildWrapper.DescriptorImpl wrapperDescriptor() {
            return jenkins.model.Jenkins.get().getDescriptorByType(ApplitoolsBuildWrapper.DescriptorImpl.class);
        }

        @Override
        public String getFunctionName() {
            return "applitools";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Applitools Eyes visual tests";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }

        /** The options' help is the wrapper's. */
        @Override
        public String getHelpFile(String fieldName) {
            return wrapperDescriptor().getHelpFile(fieldName);
        }

        /** Same form handling as the wrapper (its "from label" / "global setting" choices). */
        @Override
        public Step newInstance(StaplerRequest2 req, @NonNull JSONObject formData) throws FormException {
            return new ApplitoolsStep(wrapperDescriptor().newInstance(req, formData));
        }

        public ListBoxModel doFillMergeBaselinesItems() {
            return wrapperDescriptor().doFillMergeBaselinesItems();
        }

        public ListBoxModel doFillDesignReviewItems() {
            return wrapperDescriptor().doFillDesignReviewItems();
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String credentialsId) {
            return wrapperDescriptor().doFillCredentialsIdItems(item, credentialsId);
        }

        @POST
        public ListBoxModel doFillReadCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String readCredentialsId) {
            return wrapperDescriptor().doFillReadCredentialsIdItems(item, readCredentialsId);
        }
    }
}
