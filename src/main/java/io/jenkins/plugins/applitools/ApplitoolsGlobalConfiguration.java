package io.jenkins.plugins.applitools;

import hudson.Extension;
import hudson.Util;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** System-wide settings shown under Manage Jenkins → System, and configurable via JCasC ({@code applitools:}). */
@Extension
@Symbol("applitools")
public class ApplitoolsGlobalConfiguration extends GlobalConfiguration {

    public static final String DEFAULT_SERVER_URL = "https://eyes.applitools.com";

    private String serverUrl;

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
}
