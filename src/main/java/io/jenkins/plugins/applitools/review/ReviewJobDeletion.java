package io.jenkins.plugins.applitools.review;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Before Jenkins deletes a job, merges the baselines of its followed PR if that PR merged. */
@Extension
public class ReviewJobDeletion extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(ReviewJobDeletion.class.getName());

    @Override
    public void onCheckDelete(Item item) {
        if (!(item instanceof Job<?, ?> job)) {
            return;
        }
        try {
            ReviewEvents.jobDeleting(job);
        } catch (RuntimeException e) {
            // Never block the deletion.
            LOGGER.log(Level.WARNING, "Could not check " + job.getFullName() + " for a merged pull request", e);
        }
    }
}
