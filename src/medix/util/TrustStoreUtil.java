package medix.util;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Older Java 8 updates (e.g. 8u111) do not know the Let's Encrypt root "ISRG Root X1" that
 * nominatim.openstreetmap.org uses, so place search failed with "PKIX path building failed".
 * {@link #install()} adds that ONE public root certificate (src/medix/resources/certs, SHA-256
 * 96:BC:EC:06:…:DF:08:C6) to the certificates Java already trusts. Nothing is switched off: every
 * connection is still fully checked. Newer Java versions already include this root.
 */
public final class TrustStoreUtil {

    private static final String[] BUNDLED = {"/medix/resources/certs/isrg-root-x1.pem"};
    private static boolean installed;

    private TrustStoreUtil() {
    }

    /** Call once at start-up, before the first HTTPS request. Never throws. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int n = 0;
            for (X509Certificate c : defaultTrustManager().getAcceptedIssuers()) {
                store.setCertificateEntry("java-" + n++, c);
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (String resource : BUNDLED) {
                try (InputStream in = TrustStoreUtil.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        ErrorLog.log("Missing certificate resource " + resource);
                        continue;
                    }
                    for (Certificate c : factory.generateCertificates(in)) {
                        store.setCertificateEntry("medix-" + n++, c);
                    }
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            SSLContext.setDefault(context); // SMTP (JavaMail STARTTLS) uses the default context
            installed = true;
        } catch (Exception e) {
            ErrorLog.log("Could not add the bundled root certificates; online place search may fail on old Java", e);
        }
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No default X509TrustManager");
    }
}
