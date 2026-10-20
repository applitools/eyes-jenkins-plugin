package io.jenkins.plugins.applitools.setup;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.util.ArrayList;
import java.util.List;

/** Contributes rows to the setup page. Implementations run in ordinal order, highest first. */
public abstract class SetupCheck implements ExtensionPoint {

    @NonNull
    public abstract List<SetupItem> run() throws InterruptedException;

    @NonNull
    public static List<SetupItem> runAll() throws InterruptedException {
        List<SetupItem> items = new ArrayList<>();
        for (SetupCheck check : ExtensionList.lookup(SetupCheck.class)) {
            try {
                items.addAll(check.run());
            } catch (RuntimeException e) {
                items.add(SetupItem.problem(
                        check.getClass().getSimpleName(), "This check failed to run: " + e, null));
            }
        }
        return items;
    }
}
