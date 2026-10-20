package io.jenkins.plugins.applitools.scm;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.util.Set;
import java.util.TreeSet;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.github_branch_source.Connector;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GitHub;

/** Reads PR labels with the job's own GitHub credentials (e.g. its GitHub App). Needs "Pull requests: read". */
@Extension(optional = true)
public class GitHubPullRequestLabels extends PullRequestLabels {

    @Override
    public Set<String> labels(@NonNull Run<?, ?> run, @NonNull ScmInfo scm) throws Exception {
        SCMSource source = SCMSource.SourceByItem.findSource(run.getParent());
        String slug = scm.repoSlug();
        String number = scm.changeId();
        if (!(source instanceof GitHubSCMSource github) || slug == null || number == null) {
            return null;
        }
        StandardCredentials credentials = Connector.lookupScanCredentials(
                source.getOwner(), github.getApiUri(), github.getCredentialsId(), github.getRepoOwner());
        GitHub gh = Connector.connect(github.getApiUri(), credentials);
        try {
            Set<String> names = new TreeSet<>();
            for (GHLabel label : gh.getRepository(slug)
                    .getPullRequest(Integer.parseInt(number))
                    .getLabels()) {
                names.add(label.getName());
            }
            return names;
        } finally {
            Connector.release(gh);
        }
    }
}
