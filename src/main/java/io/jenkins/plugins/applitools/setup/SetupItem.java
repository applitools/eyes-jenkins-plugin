package io.jenkins.plugins.applitools.setup;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/** One row of the setup page: what was checked, what was found, and how to fix it. */
public class SetupItem {

    public enum Status {
        OK("symbol-checkmark-circle plugin-ionicons-api", "--success-color"),
        WARNING("symbol-warning plugin-ionicons-api", "--warning-color"),
        PROBLEM("symbol-close-circle plugin-ionicons-api", "--error-color"),
        INFO("symbol-information-circle plugin-ionicons-api", "--text-color-secondary");

        private final String icon;
        private final String color;

        Status(String icon, String color) {
            this.icon = icon;
            this.color = color;
        }
    }

    private final Status status;
    private final String title;
    private final String detail;
    private final String fix;

    public SetupItem(@NonNull Status status, @NonNull String title, @NonNull String detail, @CheckForNull String fix) {
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.fix = fix;
    }

    public static SetupItem ok(String title, String detail) {
        return new SetupItem(Status.OK, title, detail, null);
    }

    public static SetupItem info(String title, String detail) {
        return new SetupItem(Status.INFO, title, detail, null);
    }

    public static SetupItem warning(String title, String detail, String fix) {
        return new SetupItem(Status.WARNING, title, detail, fix);
    }

    public static SetupItem problem(String title, String detail, String fix) {
        return new SetupItem(Status.PROBLEM, title, detail, fix);
    }

    public Status getStatus() {
        return status;
    }

    public String getIcon() {
        return status.icon;
    }

    public String getColor() {
        return status.color;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public String getFix() {
        return fix;
    }

    @Override
    public String toString() {
        return status + " " + title + ": " + detail + (fix != null ? " → " + fix : "");
    }
}
