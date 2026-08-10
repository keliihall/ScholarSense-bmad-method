package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Strict parser and canonicalizer for controlled QMDP/QSHM JSON bytes. */
final class StrictQualityContractJson {
    private static final BigInteger MAX_SAFE_INTEGER =
            BigInteger.valueOf(9_007_199_254_740_991L);
    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private static final Comparator<String> UNICODE_CODE_POINT_ORDER =
            StrictQualityContractJson::compareCodePoints;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private StrictQualityContractJson() {}

    static JsonNode parse(byte[] payload) {
        if (payload == null || payload.length == 0 || startsWithBom(payload)) {
            throw invalid();
        }
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
            scanTokens(source);
            try (JsonParser parser = JSON.createParser(source)) {
                JsonNode root = JSON.readTree(parser);
                if (root == null || parser.nextToken() != null) {
                    throw invalid();
                }
                validateValue(root);
                return root;
            }
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (CharacterCodingException failure) {
            throw invalid(failure);
        } catch (RuntimeException failure) {
            throw invalid(failure);
        } catch (Exception failure) {
            throw invalid(failure);
        }
    }

    static String rawDigest(byte[] payload) {
        return digest(payload);
    }

    static String canonicalDigest(JsonNode value) {
        return digest(canonicalBytes(value));
    }

    static byte[] canonicalBytes(JsonNode value) {
        validateValue(value);
        StringBuilder result = new StringBuilder();
        appendCanonical(result, value);
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void scanTokens(String source) throws Exception {
        try (JsonParser parser = JSON.createParser(source)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                    throw invalid();
                }
                if (token == JsonToken.VALUE_NUMBER_INT) {
                    String lexical = parser.getText();
                    if ("-0".equals(lexical)
                            || parser.getBigIntegerValue().abs().compareTo(MAX_SAFE_INTEGER) > 0) {
                        throw invalid();
                    }
                }
                if (token == JsonToken.PROPERTY_NAME || token == JsonToken.VALUE_STRING) {
                    requireUnicodeScalars(parser.getString());
                }
            }
        }
    }

    private static void validateValue(JsonNode value) {
        if (value == null) throw invalid();
        if (value.isNull() || value.isBoolean()) return;
        if (value.isTextual()) {
            requireUnicodeScalars(value.textValue());
            return;
        }
        if (value.isIntegralNumber()) {
            if (value.bigIntegerValue().abs().compareTo(MAX_SAFE_INTEGER) > 0) throw invalid();
            return;
        }
        if (value.isArray()) {
            value.forEach(StrictQualityContractJson::validateValue);
            return;
        }
        if (value.isObject()) {
            value.forEachEntry((name, child) -> {
                requireUnicodeScalars(name);
                validateValue(child);
            });
            return;
        }
        throw invalid();
    }

    private static void appendCanonical(StringBuilder target, JsonNode value) {
        if (value.isNull()) {
            target.append("null");
        } else if (value.isBoolean()) {
            target.append(value.booleanValue());
        } else if (value.isIntegralNumber()) {
            target.append(value.bigIntegerValue());
        } else if (value.isTextual()) {
            appendString(target, value.textValue());
        } else if (value.isArray()) {
            target.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) target.append(',');
                appendCanonical(target, value.get(index));
            }
            target.append(']');
        } else if (value.isObject()) {
            List<String> names = new ArrayList<>();
            names.addAll(value.propertyNames());
            names.sort(UNICODE_CODE_POINT_ORDER);
            target.append('{');
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) target.append(',');
                String name = names.get(index);
                appendString(target, name);
                target.append(':');
                appendCanonical(target, value.required(name));
            }
            target.append('}');
        } else {
            throw invalid();
        }
    }

    private static void appendString(StringBuilder target, String value) {
        requireUnicodeScalars(value);
        target.append('"');
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        target.append("\\u00");
                        target.append(Character.forDigit((codePoint >>> 4) & 0xf, 16));
                        target.append(Character.forDigit(codePoint & 0xf, 16));
                    } else {
                        target.appendCodePoint(codePoint);
                    }
                }
            }
        }
        target.append('"');
    }

    private static void requireUnicodeScalars(String value) {
        if (value == null) throw invalid();
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid();
                }
                index++;
            } else if (Character.isLowSurrogate(unit)) {
                throw invalid();
            }
        }
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftPoint = left.codePointAt(leftOffset);
            int rightPoint = right.codePointAt(rightOffset);
            if (leftPoint != rightPoint) return Integer.compare(leftPoint, rightPoint);
            leftOffset += Character.charCount(leftPoint);
            rightOffset += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }

    private static boolean startsWithBom(byte[] payload) {
        return payload.length >= UTF8_BOM.length
                && payload[0] == UTF8_BOM[0]
                && payload[1] == UTF8_BOM[1]
                && payload[2] == UTF8_BOM[2];
    }

    private static String digest(byte[] payload) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IngestionQualityApplicationException invalid() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_CONTRACT_INVALID");
    }

    private static IngestionQualityApplicationException invalid(Throwable cause) {
        return new IngestionQualityApplicationException(
                "INGESTION_QUALITY_CONTRACT_INVALID", cause);
    }
}
