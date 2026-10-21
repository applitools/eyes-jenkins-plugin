package io.jenkins.plugins.applitools.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.InvisibleAction;

/**
 * Marks a build that reports to GitHub with a token from the {@code applitools} step ({@code githubCredentialsId}),
 * rather than through a multibranch GitHub source. Saved with the build, so results posted after it finishes (when
 * reviewers act in Eyes) still know where to go.
 */
public class GitHubConnectionAction extends InvisibleAction {

    private final String apiUrl;
    private final String credentialsId;
    private final String repo;
    private final String commit;
    private final String pullRequest;

    public GitHubConnectionAction(
            @CheckForNull String apiUrl, String credentialsId, String repo, String commit, @CheckForNull String pullRequest) {
        this.apiUrl = apiUrl;
        this.credentialsId = credentialsId;
        this.repo = repo;
        this.commit = commit;
        this.pullRequest = pullRequest;
    }

    @CheckForNull
    public String getApiUrl() {
        return apiUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getRepo() {
        return repo;
    }

    public String getCommit() {
        return commit;
    }

    @CheckForNull
    public String getPullRequest() {
        return pullRequest;
    }
}
