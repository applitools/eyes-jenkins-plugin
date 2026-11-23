package io.jenkins.plugins.applitools.setup;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import io.jenkins.plugins.applitools.review.MergeHistory;
import io.jenkins.plugins.applitools.review.UnmergedBaselines;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

/** Manage Jenkins → Applitools setup: checks every piece of the setup live, on request, with a fix for each problem. */
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

    /** Merged PRs whose Eyes baselines aren't merged yet, for the page's "Unmerged baselines" table. */
    public List<UnmergedBaselines.Item> getUnmergedBaselines() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return UnmergedBaselines.get().all();
    }

    /** Whether PRs with unmerged baselines get a comment and label. */
    public boolean isCommentOnUnmergedBaselines() {
        return io.jenkins.plugins.applitools.ApplitoolsGlobalConfiguration.get().isCommentOnUnmergedBaselines();
    }

    /** The latest Eyes baseline merge outcomes, newest first, for the page's "Recent baseline merges" table. */
    public List<MergeHistory.Entry> getRecentMerges() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return MergeHistory.get().recent();
    }

    /**
     * Runs every check and shows the results. A POST from the page's button: the checks call Eyes and GitHub, so
     * merely following a link (e.g. from another site) must not run them.
     */
    @POST
    public void doRun(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException, InterruptedException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        req.setAttribute("items", SetupCheck.runAll());
        req.getView(this, "index.jelly").forward(req, rsp);
    }
}
