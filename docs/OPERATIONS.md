# Resource usage and limits

What the plugin costs a Jenkins controller in memory, threads, network and disk, with the limits that keep it bounded. Numbers are calculated from measurements taken 2026-10-20 against eyes.applitools.com with a 10-test batch. Formulas are included so you can redo them for your batch sizes.

## Summary

| Resource | Typical | Worst case (all limits hit) |
| --- | --- | --- |
| Heap held long-term | ~1.5 KB per PR being followed | **~1 MB** (500 followed builds) |
| Heap per Eyes call (short-lived) | ~40 KB (10 tests) | ~4 MB (1,000-test batch), one call at a time |
| Threads | **None per review.** One background pass at a time, one shared HTTP client | Same |
| Network to Eyes (review watcher) | ~400 KB per PR reviewed within a day | ~1.2 MB/h steady, ~18 MB in a burst hour |
| Disk | ~1.2 KB per followed build, ~5 KB of results per build | ~600 KB registry file |

**A hung build costs the watcher nothing.** A build is only followed after its `applitools { }` block finishes and its results are in.

## What runs, and when

| Activity | When | Cost |
| --- | --- | --- |
| **During the build** | When the `applitools` block ends | Closes the batch, then polls Eyes every 5 s until rendering finishes (at most `resultsTimeout`, default 300 s, so ≤ 60 small requests). Runs on the build's own executor, like any build step. |
| **Review watcher** | A background pass once a minute | Re-reads Eyes for builds whose changes are still waiting on reviewers, and updates the PR checks. |
| **Label and merge webhooks** | When GitHub sends them | One short task on Jenkins' shared timer pool. A merge polls Eyes every 5 s, for at most 10 minutes. |

### Which builds the watcher follows

- **Only pull request builds,** and only while they can still change. Branch builds (e.g. `main`) post their results when the build ends and aren't followed afterwards: nobody reviews them, so following them would hold results that never settle. (Opt in with **Keep updating results for branch builds** in Manage Jenkins → System.)
- **An open PR stays followed even when fully reviewed,** so label changes, its merge, and a reviewer changing their mind in Eyes still update it. Fully reviewed PRs are re-read from Eyes only every 30 minutes.
- **One per job.** A new build of a PR or branch replaces the older one.
- **Dropped automatically,** checked on every pass without network calls: when its build is deleted, when its job is gone or disabled (Jenkins disables the jobs of closed PRs and deleted branches), when its job has a newer finished build, when the PR closes, or after **14 days**. Before dropping a closed PR, the plugin asks GitHub whether it **merged**; if so, it runs the baseline merge the missed webhook would have run.
- **At most 500.** Beyond that, the longest-followed are dropped (and a warning is logged).

## How polling is bounded

| Fallback | Behavior |
| --- | --- |
| **Polling slows with age** | Every minute for the first hour, every 5 minutes for the rest of the first day, every 30 minutes after that. Reviewers rarely act on week-old PRs. |
| **Backoff on errors** | After consecutive Eyes errors (outage, timeouts), the interval doubles each time, up to an hour. One success resets it. |
| **Time budget per pass** | A pass stops after 50 s. The builds polled longest ago go first, so none starve. |
| **No overlapping passes** | Jenkins never starts a pass while the previous one is still running. |
| **One shared HTTP client** | All Eyes calls share one connection pool and selector thread, rather than creating them per request. |
| **Compressed responses** | Eyes responses are requested gzipped: 6× less traffic. |
| **Saves only on change** | The registry file is rewritten only when a result, label or tracked build changes. |

## Network: calculated

**Measured (10-test batch):**

| Request | Raw | Gzipped (what the plugin uses) |
| --- | --- | --- |
| Results (`GET /api/v1/batches/{id}`) | 7.6 KB | **1.2 KB** (≈ 120 B per test) |
| Batch status (`…/bypointerid`), only while the build waits for rendering | n/a | 0.8 KB |
| Session detail, only for designers-only approval, once per changed test | 8.8 KB | 3.3 KB |
| Latency | 0.16–0.23 s | |

**Polls for one followed build:** at most **960** before the 14-day limit.

| Period | Interval | Polls |
| --- | --- | --- |
| Hour 1 | 1 min | 60 |
| Hours 2–24 | 5 min | 23 × 12 = 276 |
| Days 2–14 | 30 min | 13 × 48 = 624 |
| **Total** | | **960** |

**Traffic for one PR** (10 tests; scale linearly at ~120 B per test per poll):

| Reviewed within | Polls | Traffic |
| --- | --- | --- |
| 1 hour | ≤ 60 | ≤ 72 KB |
| 1 day | ≤ 336 | ≤ 400 KB |
| Never (dropped at 14 days) | 960 | ≈ 1.15 MB (would be 7.3 MB uncompressed) |

For a 100-test batch, multiply by 10: about 4 MB per PR reviewed within a day.

**At the cap of 500 followed builds:**
- **Steady state** (all older than a day): 500 × 2 polls/h = **1,000 requests/h** (one every 3.6 s), **≈ 1.2 MB/h**, with about 3 s of request time per minute-long pass.
- **Burst** (all 500 started in the same hour): 500 due every minute × 0.2 s = 100 s of work, but the 50 s budget caps a pass at about 250 polls. Each build is then polled about every 2 minutes: **≈ 18 MB in that hour**, falling to the steady state as the builds age.

**Designers from an Eyes team** add two small calls (key validation, then the team list: ~0.5 KB gzipped), at most once every 10 minutes per key, and only while a review is checking who approved.

**Designers-only approval** adds one 3.3 KB session read per changed test, but only once all changes are accepted and the plugin is checking *who* accepted them. Typically a few polls.

### Jobs that aren't multibranch (token reporting)

These PRs are also polled on GitHub (same schedule: 1, 5, then 30 minutes), to catch label changes and merges without webhooks. Each poll is a **conditional request**: GitHub answers an unchanged PR with `304 Not Modified`, **no body, and no cost against the token's 5,000 requests/hour** (verified 2026-10-21). Only a PR that actually changed costs a full read (~21 KB; GitHub doesn't compress it). Results are posted as commit statuses: about 4 small POSTs per build, plus one per later state change.

## Memory: calculated

- **Per followed build:** ~1.2 KB serialized (measured: 3,730 bytes for 3 entries) plus ~60 bytes per changed test ID. That's about **1.5 KB on the heap** with object overhead. **At the cap: ~0.75–1 MB.**
- **Per Eyes call (short-lived):** the decompressed response (~760 B per test) plus its parsed JSON (3–5×), so ~40 KB for 10 tests and ~4 MB for 1,000 tests. Calls run one at a time, and the memory is freed when the call completes.
- **Results kept on each build** (for the build page): ~5.4 KB for 10 tests (measured), about 500 B per test. This is part of the build record and goes away with build discarding, like test reports.

## Threads

- **No thread per review, ever.** A single background pass handles all followed builds, one after another.
- **One shared HTTP client:** one selector thread, plus pooled connections that close when idle. It's rebuilt only if Jenkins' proxy settings change.
- **Webhooks** (label changes, merges) run as short tasks on Jenkins' shared timer pool.

## Disk

- **Registry file:** `$JENKINS_HOME/io.jenkins.plugins.applitools.review.ReviewRegistry.xml`, ~1.2 KB per followed build (≤ ~600 KB at the cap). Written only when something changes.
- **Build records:** ~500 bytes per test in each build's `build.xml`.

## Tuning

All limits are Java system properties, set on the controller's command line (`-D…`, e.g. in `JAVA_OPTS`). The defaults suit most installations.

| Property | Default | Effect |
| --- | --- | --- |
| `io.jenkins.plugins.applitools.review.PendingReview.minPollSeconds` | `60` | Base polling interval. The others scale from it: ×5 after an hour, ×30 after a day, backoff up to ×60. |
| `io.jenkins.plugins.applitools.ReviewWatcher.budgetSeconds` | `50` | Most time a watcher pass may spend |
| `io.jenkins.plugins.applitools.ReviewWatcher.periodSeconds` | `60` | How often a pass runs |
| `io.jenkins.plugins.applitools.review.ReviewRegistry.maxReviews` | `500` | Most builds followed at once |
| `io.jenkins.plugins.applitools.review.ReviewRegistry.maxAgeDays` | `14` | Followed builds older than this are dropped |
| `io.jenkins.plugins.applitools.BaselineMerge.timeoutSeconds` | `600` | Longest wait for an Eyes baseline merge |

The `applitools` step's `resultsTimeout` option (default 300 s) bounds how long a build waits for Eyes to finish rendering.

**Manage Jenkins → Applitools setup** shows how many builds are followed, which are waiting on reviewers, and how old the oldest is.
