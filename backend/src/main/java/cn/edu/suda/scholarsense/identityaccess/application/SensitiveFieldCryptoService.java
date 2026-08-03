package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.Visibility;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Decrypts CLEAR fields only and owns the plaintext lifetime through the callback. */
public final class SensitiveFieldCryptoService {
    private final SensitiveFieldCryptoPort cryptoPort;

    public SensitiveFieldCryptoService(SensitiveFieldCryptoPort cryptoPort) {
        this.cryptoPort = Objects.requireNonNull(cryptoPort, "cryptoPort");
    }

    public <T> Optional<T> withDecryptedClearValue(
            Visibility visibility,
            SensitiveFieldCryptoContext context,
            FieldCiphertextEnvelope envelope,
            Function<byte[], T> consumer) {
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(consumer, "consumer");
        if (visibility != Visibility.CLEAR) {
            return Optional.empty();
        }
        try (WipeablePlaintext plaintext = cryptoPort.decrypt(context, envelope)) {
            return Optional.ofNullable(plaintext.use(consumer));
        }
    }
}
