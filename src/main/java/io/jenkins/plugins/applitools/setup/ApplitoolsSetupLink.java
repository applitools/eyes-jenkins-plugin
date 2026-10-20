package io.jenkins.plugins.applitools.setup;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import java.util.List;
import jenkins.model.Jenkins;

/** Manage Jenkins → Applitools setup: checks every piece of the setup live, with a fix for each problem. */
@Extension
public class ApplitoolsSetupLink extends ManagementLink {

    @Override
    public String getIconFileName() {
        return "symbol-eye-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Applitools setup";
    }

    @Override
    public String getDescription() {
        return "Check that Applitools Eyes visual review is set up correctly: keys, GitHub, webhooks and jobs.";
    }

    @Override
    public String getUrlName() {
        return "applitools-setup";
    }

    @NonNull
    @Override
    public Permission getRequiredPermission() {
        return Jenkins.ADMINISTER;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.STATUS;
    }

    /** Runs every check; called when the page renders. */
    public List<SetupItem> getItems() throws InterruptedException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return SetupCheck.runAll();
    }
}
