package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Comparator;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** Ephemeral, synthetic, non-production mTLS material created only in a test temp directory. */
final class PublicIntegrationMtlsTestSupport implements AutoCloseable {

    private static final char[] TEST_PASSWORD = "pic-non-production-only".toCharArray();

    private final Path directory;
    private final SSLContext sslContext;
    private final SSLContext trustOnlySslContext;
    private final String certificateThumbprint;

    private PublicIntegrationMtlsTestSupport(
            Path directory,
            SSLContext sslContext,
            SSLContext trustOnlySslContext,
            String certificateThumbprint) {
        this.directory = directory;
        this.sslContext = sslContext;
        this.trustOnlySslContext = trustOnlySslContext;
        this.certificateThumbprint = certificateThumbprint;
    }

    static PublicIntegrationMtlsTestSupport create() throws Exception {
        Path directory = Files.createTempDirectory("scholarsense-pic-mtls-");
        Path keyStorePath = directory.resolve("synthetic-non-production.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", "pic-synthetic",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-dname", "CN=localhost,OU=NON-PRODUCTION,O=ScholarSense Test,L=Suzhou,C=CN",
                "-ext", "SAN=dns:localhost",
                "-validity", "2",
                "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(),
                "-storepass", String.valueOf(TEST_PASSWORD),
                "-keypass", String.valueOf(TEST_PASSWORD),
                "-noprompt")
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("PIC_TEST_KEYTOOL_FAILED: "
                    + new String(output, java.nio.charset.StandardCharsets.UTF_8));
        }
        KeyStore keyStore = KeyStore.getInstance(keyStorePath.toFile(), TEST_PASSWORD);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, TEST_PASSWORD);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        SSLContext trustOnlyContext = SSLContext.getInstance("TLSv1.3");
        trustOnlyContext.init(null, trustManagers.getTrustManagers(), null);
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate("pic-synthetic");
        String thumbprint = "sha256:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(certificate.getEncoded()));
        return new PublicIntegrationMtlsTestSupport(
                directory, context, trustOnlyContext, thumbprint);
    }

    SSLContext sslContext() {
        return sslContext;
    }

    SSLContext trustOnlySslContext() {
        return trustOnlySslContext;
    }

    String certificateThumbprint() {
        return certificateThumbprint;
    }

    HttpsServer server(HttpHandler handler) throws IOException {
        HttpsServer server = HttpsServer.create(
                new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters sslParameters = sslContext.getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(true);
                parameters.setSSLParameters(sslParameters);
            }
        });
        server.createContext("/", handler);
        server.start();
        return server;
    }

    @Override
    public void close() throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
