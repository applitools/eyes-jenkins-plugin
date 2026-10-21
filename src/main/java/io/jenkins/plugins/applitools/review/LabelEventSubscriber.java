package io.jenkins.plugins.applitools.review;

import hudson.Extension;
import hudson.model.Item;
import java.util.Set;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.kohsuke.github.GHEvent;

/**
 * Feeds GitHub's {@code pull_request} webhooks (label changes, closed PRs) to {@link ReviewEvents}, through the GitHub
 * plugin's webhook endpoint (the same one multibranch jobs use). Only loaded when the GitHub plugin is installed;
 * without it, PRs are polled instead.
 */
@Extension(optional = true)
public class LabelEventSubscriber extends GHEventsSubscriber {

    @Override
    protected boolean isApplicable(Item item) {
        return true;
    }

    @Override
    protected Set<GHEvent> events() {
        return Set.of(GHEvent.PULL_REQUEST);
    }

    @Override
    protected void onEvent(GHSubscriberEvent event) {
        ReviewEvents.onPullRequestWebhook(event.getPayload());
    }
}
