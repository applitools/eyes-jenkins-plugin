package io.jenkins.plugins.applitools;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ApplitoolsBuilderTest {

    @Test
    void configRoundtrip(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        ApplitoolsBuilder builder = new ApplitoolsBuilder("My App");
        builder.setBatchName("nightly");
        project.getBuildersList().add(builder);

        project = j.configRoundtrip(project);

        j.assertEqualDataBoundBeans(builder, project.getBuildersList().get(0));
    }

    @Test
    void freestyleBuildLogs(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(new ApplitoolsBuilder("My App"));

        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        j.assertLogContains("[Applitools] app=My App", build);
        j.assertLogContains("server=" + ApplitoolsGlobalConfiguration.DEFAULT_SERVER_URL, build);
    }

    @Test
    void pipelineStep(JenkinsRule j) throws Exception {
        j.createOnlineSlave();
        WorkflowJob job = j.createProject(WorkflowJob.class, "pipe");
        job.setDefinition(new CpsFlowDefinition(
                "node { applitools appName: 'My App', batchName: 'build-${BUILD_NUMBER}' }", true));

        WorkflowRun run = j.buildAndAssertSuccess(job);

        j.assertLogContains("[Applitools] app=My App batch=build-1", run);
    }
}
