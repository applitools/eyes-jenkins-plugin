package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Links from Eyes results are shown on build pages, so only http(s) links survive: no javascript: links. */
class ResultLinksTest {

    @Test
    void onlyWebLinksAreKept() {
        assertThat(BatchResults.safeUrl("https://eyes.applitools.com/app/batches/1"),
                is("https://eyes.applitools.com/app/batches/1"));
        assertThat(BatchResults.safeUrl("http://eyes.internal:8080/x"), is("http://eyes.internal:8080/x"));
        assertThat(BatchResults.safeUrl("javascript:alert(document.domain)"), nullValue());
        assertThat(BatchResults.safeUrl("JavaScript:alert(1)"), nullValue());
        assertThat(BatchResults.safeUrl("  javascript:alert(1)"), nullValue());
        assertThat(BatchResults.safeUrl("data:text/html,<script>alert(1)</script>"), nullValue());
        assertThat(BatchResults.safeUrl("/relative/path"), nullValue());
        assertThat(BatchResults.safeUrl("https:no-host"), nullValue());
        assertThat(BatchResults.safeUrl(null), nullValue());
    }

    @Test
    void unsafeLinksDisappearFromResults() {
        // Also how builds stored before the check behave: the check runs when links are read.
        BatchResults results = new BatchResults("b1", "Batch", "javascript:alert(1)", 0, 0, 1, 0, 0, 1,
                List.of(new BatchResults.Test("s1", "Login", "Chrome", "unresolved", "javascript:alert(2)")));

        assertThat(results.getUrl(), nullValue());
        assertThat(results.getTests().get(0).getUrl(), nullValue());
        assertThat(results.toMap().get("url"), nullValue());
    }
}
