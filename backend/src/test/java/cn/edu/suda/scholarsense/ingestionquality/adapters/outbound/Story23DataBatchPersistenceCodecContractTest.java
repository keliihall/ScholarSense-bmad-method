package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Strict bytea/text boundary required for persisted canonical batch identities and watermarks. */
class Story23DataBatchPersistenceCodecContractTest {

    @Test
    void roundTripsNullScalarAndSupplementaryUnicodeWithoutJdbcTextLoss() {
        String value = "student" + Character.toString(0) + "水位:"
                + Character.toString(0x1f680);

        byte[] encoded = encode(value);

        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), encoded);
        assertEquals(value, decode(encoded));
    }

    @Test
    void encodingRejectsAnUnpairedJavaSurrogateInsteadOfReplacingIt() {
        assertThrows(IllegalArgumentException.class,
                () -> encode("bad-" + Character.toString(0xd800) + "-value"));
        assertThrows(IllegalArgumentException.class,
                () -> encode("bad-" + Character.toString(0xdc00) + "-value"));
    }

    @Test
    void decodingRejectsEveryRepresentativeMalformedUtf8Shape() {
        List<byte[]> malformed = List.of(
                new byte[] {(byte) 0x80},
                new byte[] {(byte) 0xc0, (byte) 0xaf},
                new byte[] {(byte) 0xe2, (byte) 0x82},
                new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80});

        for (byte[] bytes : malformed) {
            assertThrows(IllegalArgumentException.class, () -> decode(bytes));
        }
    }

    private static byte[] encode(String value) {
        return (byte[]) invoke("encodeUtf8", new Class<?>[] {String.class}, value);
    }

    private static String decode(byte[] value) {
        return (String) invoke("decodeUtf8", new Class<?>[] {byte[].class}, value);
    }

    private static Object invoke(String name, Class<?>[] parameterTypes, Object argument) {
        try {
            Class<?> codec = Class.forName(
                    "cn.edu.suda.scholarsense.ingestionquality.adapters.outbound."
                            + "DataBatchPersistenceCodec");
            Method method = codec.getDeclaredMethod(name, parameterTypes);
            assertTrue(Modifier.isStatic(method.getModifiers()), name + " must be static");
            method.setAccessible(true);
            return method.invoke(null, argument);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(name + " failed", failure.getCause());
        } catch (ReflectiveOperationException missingApi) {
            throw new AssertionError(
                    "Story 2.3 requires DataBatchPersistenceCodec." + name, missingApi);
        }
    }
}
