package io.jenkins.plugins.applitools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

import hudson.ProxyConfiguration;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EyesClientSharingTest {

    @Test
    void oneClientUntilProxySettingsChange(JenkinsRule j) {
        var first = EyesClient.client();
        assertThat(EyesClient.client(), sameInstance(first));

        j.jenkins.setProxy(new ProxyConfiguration("proxy.example", 3128));

        assertThat(EyesClient.client(), not(sameInstance(first)));
    }
}
