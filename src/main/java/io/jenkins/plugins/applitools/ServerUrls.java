package io.jenkins.plugins.applitools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Checks the URLs the plugin sends keys and tokens to (the Eyes server, the GitHub API). A Jenkinsfile can set both,
 * and the requests come from the controller, so they must not reach addresses only the controller can: link-local
 * addresses and cloud metadata services in particular. Keys also aren't sent over plain http to other machines
 * unless an admin allows it.
 */
public final class ServerUrls {

    /** Metadata services that aren't link-local: AWS's IPv6 endpoint and GCP's host names. */
    private static final Set<String> METADATA =
            Set.of("fd00:ec2:0:0:0:0:0:254", "metadata.google.internal", "metadata.goog", "metadata");

    private ServerUrls() {}

    /** A URL the plugin refused to use. */
    public static class RefusedUrlException extends IOException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        RefusedUrlException(String message) {
            super(message);
        }
    }

    /**
     * @param what what the URL is for, e.g. "Eyes server URL", for the error message
     * @return the URL, if it's an http(s) URL that doesn't point at a reserved address
     */
    @NonNull
    public static String check(@NonNull String url, @NonNull String what) throws RefusedUrlException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new RefusedUrlException(what + " isn't a valid URL: " + url);
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new RefusedUrlException(what + " must start with https:// : " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new RefusedUrlException(what + " has no host name: " + url);
        }
        if (uri.getRawUserInfo() != null) {
            throw new RefusedUrlException(what + " must not contain a user name or password");
        }
        if (isReserved(host)) {
            throw new RefusedUrlException(what + " points at a reserved address (" + host
                    + "), such as a cloud metadata service. Use your Eyes server's or GitHub's address.");
        }
        if (scheme.equals("http") && !isLoopback(host) && !httpAllowed()) {
            throw new RefusedUrlException(what + " must use https:// so keys aren't sent unencrypted: " + url
                    + ". (An admin can allow http:// under Manage Jenkins → System → Applitools Eyes → Advanced.)");
        }
        return url;
    }

    private static boolean httpAllowed() {
        return jenkins.model.Jenkins.getInstanceOrNull() != null
                && ApplitoolsGlobalConfiguration.get().isAllowHttp();
    }

    /** Plain http to this machine never crosses the network. */
    private static boolean isLoopback(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (bare.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(bare)) {
                if (!address.isLoopbackAddress()) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isReserved(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (METADATA.contains(bare.toLowerCase(Locale.ROOT))) {
            return true;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(bare);
        } catch (UnknownHostException e) {
            return false; // the request itself will fail
        }
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress()
                    || METADATA.contains(address.getHostAddress())) {
                return true;
            }
        }
        return false;
    }

    /** Same as {@link #check} for an optional URL; {@code null} passes. */
    @CheckForNull
    public static String checkOptional(@CheckForNull String url, @NonNull String what) throws RefusedUrlException {
        return url != null ? check(url, what) : null;
    }
}
