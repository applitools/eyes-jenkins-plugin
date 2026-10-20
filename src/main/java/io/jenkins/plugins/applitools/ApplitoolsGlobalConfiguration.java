package io.jenkins.plugins.applitools;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.Util;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import java.util.List;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** System-wide defaults shown under Manage Jenkins → System, and configurable via JCasC ({@code applitools:}). */
@Extension
@Symbol("applitools")
public class ApplitoolsGlobalConfiguration extends GlobalConfiguration {

    public static final String DEFAULT_SERVER_URL = "https://eyes.applitools.com";
    public static final String DEFAULT_CREDENTIALS_ID = "applitools-api-key";

    private String serverUrl;
    private String credentialsId;

    public static ApplitoolsGlobalConfiguration get() {
        return GlobalConfiguration.all().getInstance(ApplitoolsGlobalConfiguration.class);
    }

    @DataBoundConstructor
    public ApplitoolsGlobalConfiguration() {
        // Restore saved values from $JENKINS_HOME/io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration.xml
        load();
    }

    public String getServerUrl() {
        return serverUrl != null ? serverUrl : DEFAULT_SERVER_URL;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = Util.fixEmptyAndTrim(serverUrl);
        save();
    }

    /** ID of the Secret text credential holding the Eyes API key. */
    public String getCredentialsId() {
        return credentialsId != null ? credentialsId : DEFAULT_CREDENTIALS_ID;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        save();
    }

    @POST
    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
        StandardListBoxModel result = new StandardListBoxModel();
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return result.includeCurrentValue(credentialsId);
        }
        return result.includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2, Jenkins.get(), StringCredentials.class, List.of(), CredentialsMatchers.always())
                .includeCurrentValue(credentialsId);
    }
}
