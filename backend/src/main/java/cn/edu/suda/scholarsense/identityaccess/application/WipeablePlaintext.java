package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Short-lived plaintext bytes. Every exposed working copy is wiped before control returns. */
public final class WipeablePlaintext implements AutoCloseable {
    private final byte[] bytes;
    private boolean destroyed;

    private WipeablePlaintext(byte[] bytes, boolean copy) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("FIELD_CRYPTO_PLAINTEXT_EMPTY");
        }
        this.bytes = copy ? Arrays.copyOf(bytes, bytes.length) : bytes;
    }

    public static WipeablePlaintext copyOf(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new WipeablePlaintext(bytes, true);
    }

    static WipeablePlaintext own(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new WipeablePlaintext(bytes, false);
    }

    public synchronized <T> T use(Function<byte[], T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (destroyed) {
            throw new IllegalStateException("FIELD_CRYPTO_PLAINTEXT_DESTROYED");
        }
        byte[] workingCopy = Arrays.copyOf(bytes, bytes.length);
        try {
            return operation.apply(workingCopy);
        } finally {
            Arrays.fill(workingCopy, (byte) 0);
        }
    }

    public synchronized boolean destroyed() {
        return destroyed;
    }

    synchronized byte[] snapshotForTesting() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public synchronized void close() {
        Arrays.fill(bytes, (byte) 0);
        destroyed = true;
    }
}
