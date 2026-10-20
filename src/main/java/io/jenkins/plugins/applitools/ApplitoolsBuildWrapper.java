package io.jenkins.plugins.applitools;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.applitools.scm.ScmInfo;
import io.jenkins.plugins.applitools.scm.ScmInfoResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildWrapper;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.credentialsbinding.masking.SecretPatterns;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * {@code applitools { ... }}: runs the body with everything the Eyes SDKs need in the environment, then closes the
 * batch. Works in Declarative and Scripted Pipeline, and as a Freestyle "Build Environment" option.
 *
 * <p>Doesn't need a workspace, so it can wrap {@code parallel} branches or several {@code node} blocks; they all share
 * one batch, closed once when the outermost block exits. Nested blocks reuse the outer batch.
 */
public class ApplitoolsBuildWrapper extends SimpleBuildWrapper {

    public static final String ENV_API_KEY = "APPLITOOLS_API_KEY";
    public static final String ENV_SERVER_URL = "APPLITOOLS_SERVER_URL";
    public static final String ENV_BATCH_ID = "APPLITOOLS_BATCH_ID";
    public static final String ENV_BATCH_NAME = "APPLITOOLS_BATCH_NAME";
    public static final String ENV_BATCH_SEQUENCE = "APPLITOOLS_BATCH_SEQUENCE";
    public static final String ENV_BRANCH_NAME = "APPLITOOLS_BRANCH_NAME";
    public static final String ENV_PARENT_BRANCH_NAME = "APPLITOOLS_PARENT_BRANCH_NAME";
    public static final String ENV_DONT_CLOSE_BATCHES = "APPLITOOLS_DONT_CLOSE_BATCHES";
    /** Marks env as set by this plugin, so nested blocks reuse the outer batch. */
    static final String ENV_MANAGED = "APPLITOOLS_JENKINS_MANAGED";

    private static final String LOG_PREFIX = "[Applitools] ";

    private String credentialsId;
    private String readCredentialsId;
    private String serverUrl;
    private String batchId;
    private String batchName;
    private String branchName;
    private String parentBranchName;
    private boolean closeBatch = true;
    private boolean waitForResults = true;
    private int resultsTimeout = DEFAULT_RESULTS_TIMEOUT;
    private ChangesPolicy onChanges = ChangesPolicy.UNSTABLE;
    /** {@code null} (jobs saved before this option existed) means true. */
    private Boolean checks;

    static final int DEFAULT_RESULTS_TIMEOUT = 300;

    @DataBoundConstructor
    public ApplitoolsBuildWrapper() {}

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    @CheckForNull
    public String getReadCredentialsId() {
        return readCredentialsId;
    }

    /** Secret text credential with a key that can read results; run keys get HTTP 403 reading them. */
    @DataBoundSetter
    public void setReadCredentialsId(String readCredentialsId) {
        this.readCredentialsId = Util.fixEmptyAndTrim(readCredentialsId);
    }

    @CheckForNull
    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = Util.fixEmptyAndTrim(serverUrl);
    }

    @CheckForNull
    public String getBatchId() {
        return batchId;
    }

    @DataBoundSetter
    public void setBatchId(String batchId) {
        this.batchId = Util.fixEmptyAndTrim(batchId);
    }

    @CheckForNull
    public String getBatchName() {
        return batchName;
    }

    @DataBoundSetter
    public void setBatchName(String batchName) {
        this.batchName = Util.fixEmptyAndTrim(batchName);
    }

    @CheckForNull
    public String getBranchName() {
        return branchName;
    }

    @DataBoundSetter
    public void setBranchName(String branchName) {
        this.branchName = Util.fixEmptyAndTrim(branchName);
    }

    @CheckForNull
    public String getParentBranchName() {
        return parentBranchName;
    }

    @DataBoundSetter
    public void setParentBranchName(String parentBranchName) {
        this.parentBranchName = Util.fixEmptyAndTrim(parentBranchName);
    }

    public boolean isCloseBatch() {
        return closeBatch;
    }

    @DataBoundSetter
    public void setCloseBatch(boolean closeBatch) {
        this.closeBatch = closeBatch;
    }

    public boolean isWaitForResults() {
        return waitForResults;
    }

    /** Wait for Eyes to finish comparing, then record results and apply {@link #getOnChanges()}. */
    @DataBoundSetter
    public void setWaitForResults(boolean waitForResults) {
        this.waitForResults = waitForResults;
    }

    public int getResultsTimeout() {
        return resultsTimeout;
    }

    /** Seconds to wait for tests still rendering after the block finishes. */
    @DataBoundSetter
    public void setResultsTimeout(int resultsTimeout) {
        this.resultsTimeout = resultsTimeout > 0 ? resultsTimeout : DEFAULT_RESULTS_TIMEOUT;
    }

    @NonNull
    public ChangesPolicy getOnChanges() {
        return onChanges;
    }

    @DataBoundSetter
    public void setOnChanges(ChangesPolicy onChanges) {
        this.onChanges = onChanges != null ? onChanges : ChangesPolicy.UNSTABLE;
    }

    public boolean isChecks() {
        return checks == null || checks;
    }

    /** Post the "Applitools / Visual tests" check on the commit. */
    @DataBoundSetter
    public void setChecks(boolean checks) {
        this.checks = checks;
    }

    /** Restores defaults for fields added after a job was saved. */
    @Serial
    protected Object readResolve() {
        if (resultsTimeout <= 0) {
            resultsTimeout = DEFAULT_RESULTS_TIMEOUT;
        }
        if (onChanges == null) {
            onChanges = ChangesPolicy.UNSTABLE;
        }
        return this;
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public void setUp(Context context, Run<?, ?> build, TaskListener listener, EnvVars initialEnvironment)
            throws IOException, InterruptedException {
        PrintStream log = listener.getLogger();
        if ("true".equals(initialEnvironment.get(ENV_MANAGED))) {
            log.println(LOG_PREFIX + "Nested block: reusing batch " + initialEnvironment.get(ENV_BATCH_ID));
            return;
        }

        String server = effectiveServerUrl();
        String credId = effectiveCredentialsId();
        String apiKey = lookupApiKey(build, credId);
        if (apiKey == null) {
            throw new AbortException(LOG_PREFIX + "No Secret text credential with ID '" + credId
                    + "' is visible to this job. Add your Eyes API key under Manage Jenkins → Credentials, or set"
                    + " credentialsId on the applitools step.");
        }

        ScmInfo scm = ScmInfoResolver.resolveFor(build, initialEnvironment);
        BatchSettings batch = BatchSettings.compute(
                scm,
                build.getParent().getFullName(),
                build.getNumber(),
                new BatchSettings.Overrides(
                        expand(initialEnvironment, batchId),
                        expand(initialEnvironment, batchName),
                        expand(initialEnvironment, branchName),
                        expand(initialEnvironment, parentBranchName)));

        context.env(ENV_API_KEY, apiKey);
        context.env(ENV_SERVER_URL, server);
        context.env(ENV_BATCH_ID, batch.batchId());
        context.env(ENV_BATCH_NAME, batch.batchName());
        // The plugin closes the batch once; stop each SDK runner from closing (and splitting) it.
        context.env(ENV_DONT_CLOSE_BATCHES, "true");
        context.env(ENV_MANAGED, "true");
        putIfNotNull(context, ENV_BATCH_SEQUENCE, batch.sequenceName());
        putIfNotNull(context, ENV_BRANCH_NAME, batch.branchName());
        putIfNotNull(context, ENV_PARENT_BRANCH_NAME, batch.parentBranchName());

        log.println(LOG_PREFIX + "Batch \"" + batch.batchName() + "\" (id " + batch.batchId() + ")");
        if (batch.branchName() != null) {
            log.println(LOG_PREFIX + "Branch " + batch.branchName()
                    + (batch.parentBranchName() != null ? " ← baseline from " + batch.parentBranchName() : ""));
        }
        log.println(LOG_PREFIX + "Server " + server + ", API key from credential '" + credId + "'");
        if (isChecks()) {
            VisualTestsCheck.started(build, listener, batch.batchName());
        }

        String explicitReadId = readCredentialsId != null
                ? readCredentialsId
                : ApplitoolsGlobalConfiguration.get().getReadCredentialsId();
        context.setDisposer(new Finish(
                server,
                credId,
                explicitReadId != null ? explicitReadId : ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID,
                explicitReadId != null,
                batch.batchId(),
                closeBatch,
                waitForResults,
                resultsTimeout,
                onChanges,
                isChecks()));
    }

    @Override
    public ConsoleLogFilter createLoggerDecorator(@NonNull Run<?, ?> build) {
        String apiKey = lookupApiKey(build, effectiveCredentialsId());
        return apiKey == null ? null : new MaskApiKey(apiKey);
    }

    private String effectiveServerUrl() {
        return serverUrl != null ? serverUrl : ApplitoolsGlobalConfiguration.get().getServerUrl();
    }

    private String effectiveCredentialsId() {
        return credentialsId != null ? credentialsId : ApplitoolsGlobalConfiguration.get().getCredentialsId();
    }

    @CheckForNull
    static String lookupApiKey(@NonNull Run<?, ?> build, @NonNull String credentialsId) {
        StringCredentials credentials =
                CredentialsProvider.findCredentialById(credentialsId, StringCredentials.class, build);
        if (credentials == null) {
            return null;
        }
        CredentialsProvider.track(build, credentials);
        return Util.fixEmptyAndTrim(credentials.getSecret().getPlainText());
    }

    @CheckForNull
    private static String expand(EnvVars env, @CheckForNull String value) {
        return value != null ? env.expand(value) : null;
    }

    private static void putIfNotNull(Context context, String key, @CheckForNull String value) {
        if (value != null) {
            context.env(key, value);
        }
    }

    /**
     * Runs when the block exits, whether the body passed or failed: closes the batch, waits for Eyes to finish, records
     * the results on the build, and applies the changes policy. Eyes being unreachable only logs a warning; the build
     * result reflects the tests, not our API calls.
     */
    private static final class Finish extends Disposer {
        @Serial
        private static final long serialVersionUID = 1L;

        private static final long POLL_MILLIS = Long.getLong(ApplitoolsBuildWrapper.class.getName() + ".pollMillis", 5000);

        private final String serverUrl;
        private final String credentialsId;
        private final String readCredentialsId;
        /** True if the user named the read credential, so its absence is worth a warning. */
        private final boolean readCredentialsExplicit;
        private final String batchId;
        private final boolean closeBatch;
        private final boolean waitForResults;
        private final int resultsTimeout;
        private final ChangesPolicy onChanges;
        private final boolean checks;

        /** Results, or why there are none (for the check's summary). */
        private record Collected(@CheckForNull BatchResults results, @CheckForNull String problem) {}

        Finish(
                String serverUrl,
                String credentialsId,
                String readCredentialsId,
                boolean readCredentialsExplicit,
                String batchId,
                boolean closeBatch,
                boolean waitForResults,
                int resultsTimeout,
                ChangesPolicy onChanges,
                boolean checks) {
            this.serverUrl = serverUrl;
            this.credentialsId = credentialsId;
            this.readCredentialsId = readCredentialsId;
            this.readCredentialsExplicit = readCredentialsExplicit;
            this.batchId = batchId;
            this.closeBatch = closeBatch;
            this.waitForResults = waitForResults;
            this.resultsTimeout = resultsTimeout;
            this.onChanges = onChanges;
            this.checks = checks;
        }

        @Override
        public void tearDown(Run<?, ?> build, TaskListener listener) throws IOException, InterruptedException {
            PrintStream log = listener.getLogger();
            String apiKey = lookupApiKey(build, credentialsId);
            if (apiKey == null) {
                log.println(LOG_PREFIX + "WARNING: credential '" + credentialsId
                        + "' disappeared; batch not closed and results not collected");
                if (checks) {
                    VisualTestsCheck.unavailable(build, listener, "The Eyes API key credential '" + credentialsId
                            + "' disappeared during the build.", true);
                }
                return;
            }
            EyesClient eyes = new EyesClient(serverUrl, apiKey);

            if (!closeBatch) {
                log.println(LOG_PREFIX + "closeBatch: false, leaving batch " + batchId + " open");
            } else if (!close(eyes, log)) {
                // Nothing ran, so there are no results to collect; the gate must not pass on an empty run.
                if (checks) {
                    VisualTestsCheck.completed(build, listener, BatchResults.empty(batchId));
                }
                return;
            }
            if (!waitForResults) {
                if (checks) {
                    VisualTestsCheck.unavailable(
                            build, listener, "`waitForResults: false`, so this build didn't wait for Eyes.", false);
                }
                return;
            }
            Collected collected = collect(reader(build, eyes, log), log);
            BatchResults results = collected.results();
            if (results != null) {
                ApplitoolsResultsAction.addTo(build, results);
                if (checks) {
                    VisualTestsCheck.completed(build, listener, results);
                }
                apply(results, build, log);
            } else if (checks) {
                String problem = collected.problem();
                VisualTestsCheck.unavailable(
                        build, listener, problem != null ? problem : "Eyes returned no results.", true);
            }
        }

        /** Client for reading results: the read key if there is one, else the run key. */
        private EyesClient reader(Run<?, ?> build, EyesClient runKeyClient, PrintStream log) {
            String readKey = lookupApiKey(build, readCredentialsId);
            if (readKey != null) {
                return new EyesClient(serverUrl, readKey);
            }
            if (readCredentialsExplicit) {
                log.println(LOG_PREFIX + "WARNING: no Secret text credential with ID '" + readCredentialsId
                        + "'; reading results with the run key instead");
            }
            return runKeyClient;
        }

        /** @return false if Eyes has never heard of the batch */
        private boolean close(EyesClient eyes, PrintStream log) throws InterruptedException {
            try {
                if (eyes.closeBatch(batchId) == EyesClient.CloseResult.NOT_FOUND) {
                    log.println(LOG_PREFIX + "No Eyes results for batch " + batchId + " (did any tests run?)");
                    return false;
                }
                log.println(LOG_PREFIX + "Closed batch " + batchId);
            } catch (IOException e) {
                log.println(LOG_PREFIX + "WARNING: could not close batch " + batchId + ": " + e.getMessage());
            }
            return true;
        }

        private Collected collect(EyesClient eyes, PrintStream log) throws InterruptedException {
            try {
                long deadline = System.currentTimeMillis() + resultsTimeout * 1000L;
                int running = eyes.runningCount(batchId);
                if (running > 0) {
                    log.println(LOG_PREFIX + "Waiting for " + running + " test(s) to finish in Eyes…");
                }
                while (running > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(POLL_MILLIS);
                    running = eyes.runningCount(batchId);
                }
                if (running > 0) {
                    log.println(LOG_PREFIX + "WARNING: " + running + " test(s) still running after " + resultsTimeout
                            + "s; recording partial results");
                }
                BatchResults results = eyes.getResults(batchId);
                if (results == null) {
                    log.println(LOG_PREFIX + "No Eyes results for batch " + batchId);
                    return new Collected(null, "Eyes has no results for batch `" + batchId + "`.");
                }
                return new Collected(results, null);
            } catch (EyesClient.UnauthorizedException e) {
                log.println(LOG_PREFIX + "WARNING: Eyes refused to share results for batch " + batchId + " (HTTP "
                        + e.getStatus() + "). Run keys can't read results: add a Secret text credential with a read"
                        + " key as '" + ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID
                        + "', or set readCredentialsId.");
                return new Collected(null, "Eyes refused to share results (HTTP " + e.getStatus() + "). Run keys"
                        + " can't read results; add a read key as `"
                        + ApplitoolsGlobalConfiguration.DEFAULT_READ_CREDENTIALS_ID + "`.");
            } catch (IOException e) {
                log.println(LOG_PREFIX + "WARNING: could not read results for batch " + batchId + ": "
                        + e.getMessage());
                return new Collected(null, "Couldn't reach Eyes to read results: " + e.getMessage());
            }
        }

        private void apply(BatchResults results, Run<?, ?> build, PrintStream log) throws AbortException {
            String line = LOG_PREFIX + results.getOutcome().getLabel() + ": " + results.getSummary()
                    + (results.getUrl() != null ? " → " + results.getUrl() : "");
            // Always log the verdict: if the body already failed, Pipeline only reports the body's error and our
            // exception would be attached to it silently.
            log.println(line);
            boolean fail = results.getOutcome() == BatchResults.Outcome.FAILED
                    || (results.getOutcome() == BatchResults.Outcome.CHANGES && onChanges == ChangesPolicy.FAILURE);
            if (fail) {
                throw new AbortException(LOG_PREFIX + "Failing the build: " + results.getOutcome().getLabel());
            }
            if (results.getOutcome() == BatchResults.Outcome.CHANGES && onChanges == ChangesPolicy.UNSTABLE) {
                build.setResult(Result.UNSTABLE);
            }
        }
    }

    /** Replaces the API key with {@code ****} in the build log, like withCredentials does. */
    private static final class MaskApiKey extends ConsoleLogFilter implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Secret pattern;

        MaskApiKey(String apiKey) {
            this.pattern = Secret.fromString(
                    SecretPatterns.getAggregateSecretPattern(Set.of(apiKey)).pattern());
        }

        @Override
        public OutputStream decorateLogger(Run build, OutputStream logger) {
            String charset = build != null ? build.getCharset().name() : StandardCharsets.UTF_8.name();
            return new SecretPatterns.MaskingOutputStream(
                    logger, () -> Pattern.compile(pattern.getPlainText()), charset);
        }
    }

    @Symbol("applitools")
    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Applitools Eyes";
        }

        @POST
        public ListBoxModel doFillReadCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String readCredentialsId) {
            return doFillCredentialsIdItems(item, readCredentialsId);
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null
                    ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    : !item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            item instanceof Queue.Task task ? Tasks.getAuthenticationOf2(task) : ACL.SYSTEM2,
                            item,
                            StringCredentials.class,
                            List.of(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
