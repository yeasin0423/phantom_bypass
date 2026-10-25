package com.phantom.hooker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Payload classes used by the hooks: a fake Process returned when a
 * blocked command is executed, and accept-all trust / hostname verifiers
 * used for universal SSL unpinning.
 */
public final class Payloads {

    private Payloads() {}

    /** TrustManager that accepts every certificate chain. */
    public static class AcceptAllTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // accept everything
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // accept everything
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** HostnameVerifier that accepts every host. */
    public static class AcceptAllHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    /** Returns a TrustManager array containing a single accept-all manager. */
    public static TrustManager[] acceptAll() {
        return new TrustManager[]{ new AcceptAllTrustManager() };
    }

    /**
     * Fake Process returned for blocked commands (Runtime.exec /
     * ProcessBuilder.start). Produces no output and exits with code 1,
     * which is what a "binary not found" scenario looks like.
     */
    public static class DummyProcess extends Process {
        private static final byte[] EMPTY = new byte[0];

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(EMPTY);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(EMPTY);
        }

        @Override
        public int waitFor() {
            return 1;
        }

        @Override
        public int exitValue() {
            return 1;
        }

        @Override
        public void destroy() {
        }
    }

    /** Filters a PackageManager list (PackageInfo or ApplicationInfo). */
    @SuppressWarnings("unchecked")
    public static java.util.List filterPackages(java.util.List list, java.util.Set<String> hidden, boolean isPackageInfo) {
        java.util.List out = new ArrayList();
        if (list == null) return out;
        for (Object o : list) {
            try {
                String name = isPackageInfo
                        ? ((android.content.pm.PackageInfo) o).packageName
                        : ((android.content.pm.ApplicationInfo) o).packageName;
                if (!hidden.contains(name)) out.add(o);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
