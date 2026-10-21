package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Who may approve a design review. Entries are Eyes login emails or {@code team:<Eyes team name>}; teams are expanded
 * to their members through the Eyes admin API, so designers are managed in Eyes, where they review. Team lookups are
 * cached for 10 minutes. If a team can't be resolved, nobody from it can approve (fail closed).
 */
public final class Designers {

    public static final String TEAM_PREFIX = "team:";
    static final long CACHE_MILLIS = TimeUnit.MINUTES.toMillis(
            Long.getLong(Designers.class.getName() + ".cacheMinutes", 10));

    /** Server + key fingerprint → the org's teams, so lookups don't repeat on every watcher pass. */
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(List<EyesClient.Team> teams, long fetchedAt) {}

    private final List<String> entries;
    private final Set<String> allowed;
    private final String problem;

    private Designers(List<String> entries, Set<String> allowed, String problem) {
        this.entries = entries;
        this.allowed = allowed;
        this.problem = problem;
    }

    /** Plain emails only (no lookups needed). */
    @NonNull
    public static Designers of(@NonNull Collection<String> emails) {
        Set<String> allowed = new TreeSet<>();
        emails.forEach(e -> allowed.add(normalize(e)));
        return new Designers(new ArrayList<>(emails), allowed, null);
    }

    /** Expands {@code team:} entries using the given key's organization. */
    @NonNull
    public static Designers resolve(@NonNull List<String> entries, @NonNull EyesClient eyes)
            throws IOException, InterruptedException {
        Set<String> allowed = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        List<EyesClient.Team> teams = null;
        for (String entry : entries) {
            if (!isTeam(entry)) {
                allowed.add(normalize(entry));
                continue;
            }
            if (teams == null) {
                teams = teams(eyes);
            }
            String wanted = entry.substring(TEAM_PREFIX.length()).trim();
            EyesClient.Team team = teams.stream()
                    .filter(t -> t.name().equalsIgnoreCase(wanted) || wanted.equals(t.id()))
                    .findFirst()
                    .orElse(null);
            if (team == null) {
                problems.add("Eyes team '" + wanted + "' not found");
            } else if (team.members().isEmpty()) {
                problems.add("Eyes team '" + wanted + "' has no members");
            } else {
                team.members().forEach(m -> allowed.add(normalize(m)));
            }
        }
        return new Designers(entries, allowed, problems.isEmpty() ? null : String.join("; ", problems));
    }

    private static List<EyesClient.Team> teams(EyesClient eyes) throws IOException, InterruptedException {
        String cacheKey = eyes.cacheKey();
        Cached cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < CACHE_MILLIS) {
            return cached.teams();
        }
        EyesClient.KeyInfo info = eyes.keyInfo();
        if (info.orgId() == null) {
            throw new IOException("Eyes didn't report an organization for this key, so its teams can't be listed");
        }
        List<EyesClient.Team> teams = eyes.teams(info.orgId());
        CACHE.put(cacheKey, new Cached(teams, System.currentTimeMillis()));
        return teams;
    }

    static void clearCache() {
        CACHE.clear();
    }

    public static boolean isTeam(String entry) {
        return entry.regionMatches(true, 0, TEAM_PREFIX, 0, TEAM_PREFIX.length());
    }

    public static boolean anyTeams(Collection<String> entries) {
        return entries.stream().anyMatch(Designers::isTeam);
    }

    private static String normalize(String user) {
        return user.trim().toLowerCase(Locale.ROOT);
    }

    /** No designers configured: anyone reviewing in Eyes may approve. */
    public boolean isAnyone() {
        return entries.isEmpty();
    }

    public boolean allows(@NonNull String user) {
        return allowed.contains(normalize(user));
    }

    /** As configured, for messages (e.g. "team:Design, dana@example.com"). */
    public String describe() {
        return String.join(", ", entries);
    }

    public Set<String> getAllowed() {
        return allowed;
    }

    /** Why some entries couldn't be resolved, if any. */
    @CheckForNull
    public String getProblem() {
        return problem;
    }
}
