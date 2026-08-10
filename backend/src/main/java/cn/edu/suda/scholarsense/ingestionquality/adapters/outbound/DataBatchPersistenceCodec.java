package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Lossless UTF-8 boundary for PostgreSQL {@code bytea} identity and hash-material fields. */
public final class DataBatchPersistenceCodec {
    private static final String INVALID = "INGESTION_QUALITY_UTF8_INVALID";

    private DataBatchPersistenceCodec() {}

    public static byte[] encodeUtf8(String value) {
        Objects.requireNonNull(value);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(INVALID, invalid);
        }
    }

    public static String decodeUtf8(byte[] value) {
        Objects.requireNonNull(value);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(INVALID, invalid);
        }
    }
}
