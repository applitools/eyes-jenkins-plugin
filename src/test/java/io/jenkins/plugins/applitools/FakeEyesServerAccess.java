package io.jenkins.plugins.applitools;

/** Lets tests in other packages drive {@link FakeEyesServer}. */
public final class FakeEyesServerAccess implements AutoCloseable {

    private final FakeEyesServer server;

    public FakeEyesServerAccess() throws java.io.IOException {
        server = new FakeEyesServer();
    }

    public String url() {
        return server.url();
    }

    public void requireReadKey(String key) {
        server.requiredReadKey = key;
    }

    public void requireMergeKey(String key) {
        server.requiredMergeKey = key;
    }

    public void invalid(String key) {
        server.invalidKeys.add(key);
    }

    @Override
    public void close() {
        server.close();
    }
}
