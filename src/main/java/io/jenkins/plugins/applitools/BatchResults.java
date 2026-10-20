package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * One Eyes batch's results, as returned by {@code GET /api/v1/batches/{pointerId}}. Stored on the build (so it's a
 * plain XStream-friendly class, not a record).
 */
public class BatchResults implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Overall verdict, worst first. */
    public enum Outcome {
        /** Tests were rejected or aborted (e.g. a checkpoint never reached Eyes). */
        FAILED("Failed"),
        /** Visual changes are waiting for review. */
        CHANGES("Changes to review"),
        /** Every test passed, or created a new baseline. */
        PASSED("Passed"),
        /** No tests reported to this batch. */
        NO_RESULTS("No results");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** One test (one browser/device combination of one test case). */
    public static class Test implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String name;
        private final String environment;
        private final String status;
        private final String url;

        public Test(String name, String environment, String status, String url) {
            this.name = name;
            this.environment = environment;
            this.status = status;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public String getEnvironment() {
            return environment;
        }

        public String getStatus() {
            return status;
        }

        public String getUrl() {
            return url;
        }
    }

    private final String batchId;
    private final String name;
    private final String url;
    private final int passed;
    private final int failed;
    private final int unresolved;
    private final int newTests;
    private final int aborted;
    private final int total;
    private final List<Test> tests;

    public BatchResults(
            String batchId,
            String name,
            String url,
            int passed,
            int failed,
            int unresolved,
            int newTests,
            int aborted,
            int total,
            List<Test> tests) {
        this.batchId = batchId;
        this.name = name;
        this.url = url;
        this.passed = passed;
        this.failed = failed;
        this.unresolved = unresolved;
        this.newTests = newTests;
        this.aborted = aborted;
        this.total = total;
        this.tests = new ArrayList<>(tests);
    }

    @NonNull
    static BatchResults parse(@NonNull String batchId, @NonNull JSONObject json) {
        JSONObject stats = json.optJSONObject("statistics");
        if (stats == null) {
            stats = new JSONObject();
        }
        List<Test> tests = new ArrayList<>();
        JSONArray array = json.optJSONArray("tests");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                JSONObject t = array.getJSONObject(i);
                tests.add(new Test(
                        t.optString("testName"), environment(t.optJSONObject("env")), t.optString("status"),
                        t.optString("sessionUrl", null)));
            }
        }
        return new BatchResults(
                batchId,
                stats.optString("name", null),
                json.optString("batchUrl", null),
                stats.optInt("passed"),
                stats.optInt("failed"),
                stats.optInt("unresolved"),
                stats.optInt("new"),
                stats.optInt("aborted"),
                stats.optInt("total", tests.size()),
                tests);
    }

    private static String environment(@CheckForNull JSONObject env) {
        if (env == null) {
            return "";
        }
        String app = env.optString("app");
        String device = env.optString("device");
        String os = env.optString("os");
        JSONObject viewport = env.optJSONObject("viewport");
        String size = viewport != null ? " " + viewport.optInt("width") + "×" + viewport.optInt("height") : "";
        String where = device.isEmpty() || "Desktop".equals(device) ? os : device;
        return (app + (where.isEmpty() ? "" : " on " + where) + size).trim();
    }

    @NonNull
    public Outcome getOutcome() {
        if (total == 0) {
            return Outcome.NO_RESULTS;
        }
        if (failed > 0 || aborted > 0) {
            return Outcome.FAILED;
        }
        if (unresolved > 0) {
            return Outcome.CHANGES;
        }
        return Outcome.PASSED;
    }

    /** One-line summary for logs and check titles. */
    @NonNull
    public String getSummary() {
        return switch (getOutcome()) {
            case NO_RESULTS -> "No Eyes tests reported to this batch";
            case FAILED -> failed + " of " + total + " failed"
                    + (aborted > 0 ? " (" + aborted + " aborted before any checkpoint reached Eyes)" : "");
            case CHANGES -> unresolved + " of " + total + " with visual changes to review";
            case PASSED -> total + " passed" + (newTests > 0 ? " (" + newTests + " new baselines)" : "");
        };
    }

    /** What {@code applitoolsResults()} returns to Pipeline scripts. */
    @NonNull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", getOutcome().name());
        map.put("summary", getSummary());
        map.put("batchId", batchId);
        map.put("batchName", name);
        map.put("url", url);
        map.put("passed", passed);
        map.put("failed", failed);
        map.put("unresolved", unresolved);
        map.put("new", newTests);
        map.put("aborted", aborted);
        map.put("total", total);
        return map;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public int getPassed() {
        return passed;
    }

    public int getFailed() {
        return failed;
    }

    public int getUnresolved() {
        return unresolved;
    }

    public int getNewTests() {
        return newTests;
    }

    public int getAborted() {
        return aborted;
    }

    public int getTotal() {
        return total;
    }

    public List<Test> getTests() {
        return Collections.unmodifiableList(tests);
    }
}
