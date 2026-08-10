package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Builds the quality-worker's redirect-free TLS 1.3 client from protected PEM mounts. */
public final class QualityWorkerMtlsHttpClientFactory {
    private static final String PKCS8_BEGIN = "-----BEGIN " + "PRIVATE KEY-----";
    private static final String PKCS8_END = "-----END " + "PRIVATE KEY-----";

    private QualityWorkerMtlsHttpClientFactory() {}

    public static HttpClient create(Path certificate, Path privateKey, Path trustBundle) {
        try {
            List<X509Certificate> chain = certificates(secureFile(certificate, false));
            List<X509Certificate> authorities = certificates(secureFile(trustBundle, false));
            PrivateKey key = privateKey(secureFile(privateKey, true));
            char[] password = randomPassword();
            try {
                KeyStore identities = KeyStore.getInstance("PKCS12");
                identities.load(null, password);
                identities.setKeyEntry(
                        "quality-worker", key, password, chain.toArray(Certificate[]::new));
                KeyManagerFactory keys = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                keys.init(identities, password);

                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, password);
                for (int index = 0; index < authorities.size(); index++) {
                    trust.setCertificateEntry("authority-" + index, authorities.get(index));
                }
                TrustManagerFactory trusts = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                trusts.init(trust);
                SSLContext tls = SSLContext.getInstance("TLSv1.3");
                tls.init(keys.getKeyManagers(), trusts.getTrustManagers(), new SecureRandom());
                return HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .sslContext(tls)
                        .version(HttpClient.Version.HTTP_2)
                        .build();
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        } catch (Exception invalid) {
            throw new IllegalStateException("INGESTION_QUALITY_MTLS_CONFIGURATION_INVALID", invalid);
        }
    }

    private static byte[] secureFile(Path candidate, boolean privateMaterial) throws Exception {
        Path path = java.util.Objects.requireNonNull(candidate).normalize();
        if (!path.isAbsolute() || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("unprotected provider material");
        }
        if (privateMaterial && Files.getFileStore(path).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(permission ->
                    permission.name().startsWith("GROUP_")
                            || permission.name().startsWith("OTHERS_"))) {
                throw new IllegalArgumentException("private provider material is too permissive");
            }
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > 256 * 1024) {
            throw new IllegalArgumentException("provider material size invalid");
        }
        return bytes;
    }

    private static List<X509Certificate> certificates(byte[] pem) throws Exception {
        try {
            var generated = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem));
            ArrayList<X509Certificate> certificates = new ArrayList<>();
            for (Certificate certificate : generated) {
                if (!(certificate instanceof X509Certificate x509)) {
                    throw new IllegalArgumentException("non-X509 certificate");
                }
                certificates.add(x509);
            }
            if (certificates.isEmpty()) throw new IllegalArgumentException("empty certificate chain");
            return List.copyOf(certificates);
        } finally {
            java.util.Arrays.fill(pem, (byte) 0);
        }
    }

    private static PrivateKey privateKey(byte[] pem) throws Exception {
        try {
            String value = new String(pem, StandardCharsets.US_ASCII);
            if (!value.startsWith(PKCS8_BEGIN)
                    || !value.stripTrailing().endsWith(PKCS8_END)) {
                throw new IllegalArgumentException("only unencrypted PKCS#8 is accepted");
            }
            String encoded = value
                    .replace(PKCS8_BEGIN, "")
                    .replace(PKCS8_END, "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(encoded);
            try {
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
                for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
                    try {
                        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
                    } catch (Exception unsupported) {
                        // Try the next approved key family.
                    }
                }
                throw new IllegalArgumentException("unsupported PKCS#8 key");
            } finally {
                java.util.Arrays.fill(der, (byte) 0);
            }
        } finally {
            java.util.Arrays.fill(pem, (byte) 0);
        }
    }

    private static char[] randomPassword() {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        try {
            return Base64.getEncoder().encodeToString(random).toCharArray();
        } finally {
            java.util.Arrays.fill(random, (byte) 0);
        }
    }
}
