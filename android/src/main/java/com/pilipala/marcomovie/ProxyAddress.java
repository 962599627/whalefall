package com.pilipala.marcomovie;

import java.net.URI;
import java.util.Locale;

/** Pure URL routing and request validation for the reserved source proxy endpoint. */
final class ProxyAddress {
    static volatile int port = 9978;
    static volatile String token = "";

    static String base() {
        return "http://127.0.0.1:" + port;
    }

    static String rewrite(String value, int port) {
        return rewrite(value, port, "");
    }

    static String rewrite(String value, int port, String sessionToken) {
        if (value == null || port < 1 || port > 65535) return value;
        try {
            String candidate =
                    value.replace("http://127.0.0.1:-1/", "http://127.0.0.1:9978/")
                            .replace("http://localhost:-1/", "http://localhost:9978/");
            URI uri = new URI(candidate);
            String host = uri.getHost();
            int original = uri.getPort();
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    || !isLoopbackHost(host)
                    || !"/proxy".equals(uri.getPath())
                    || original < 9978
                    || original > 9999) return value;
            String query = uri.getRawQuery();
            if (sessionToken != null
                    && !sessionToken.isEmpty()
                    && (query == null || !query.contains("_mm_token="))) {
                query =
                        (query == null || query.isEmpty() ? "" : query + "&")
                                + "_mm_token="
                                + sessionToken;
            }
            return "http://127.0.0.1:"
                    + port
                    + uri.getRawPath()
                    + (query == null ? "" : "?" + query)
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (Exception ignored) {
            return value;
        }
    }

    static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String bare = host.trim();
        if (bare.startsWith("[")) {
            int close = bare.indexOf(']');
            if (close < 0) return false;
            bare = bare.substring(0, close + 1);
        } else {
            int colon = bare.indexOf(':');
            if (colon >= 0) bare = bare.substring(0, colon);
        }
        return "127.0.0.1".equals(bare)
                || "localhost".equalsIgnoreCase(bare)
                || "[::1]".equals(bare);
    }

    /**
     * Rejects DNS-rebinding Host headers, cross-origin browser requests, and mismatched session
     * tokens.
     */
    static boolean isAllowedRequest(
            String hostHeader,
            String originHeader,
            String secFetchSite,
            String requestToken,
            String expectedToken,
            boolean requireToken) {
        if (hostHeader != null && !hostHeader.isEmpty() && !isLoopbackHost(hostHeader))
            return false;
        if (originHeader != null && !originHeader.isEmpty()) return false;
        if (secFetchSite != null
                && !secFetchSite.isEmpty()
                && !"none".equalsIgnoreCase(secFetchSite.trim())
                && !"same-origin".equalsIgnoreCase(secFetchSite.trim())) return false;
        if (expectedToken != null && !expectedToken.isEmpty()) {
            if (requireToken) return expectedToken.equals(requestToken);
            if (requestToken != null && !requestToken.isEmpty())
                return expectedToken.equals(requestToken);
        }
        return true;
    }
}
