package cn.edu.suda.scholarsense.identityaccess.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Canonical UTF-8/LF digest shared by V2 sources, snapshots, and PostgreSQL readback. */
public final class ResponsibilityV2LineageDigest {
    public static final String PROFILE =
            "RESPONSIBILITY-LINEAGE-DIGEST-1.0.0";
    private static final DateTimeFormatter UTC_MICROSECOND =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    private ResponsibilityV2LineageDigest() {}

    public static String canonical(
            List<ResponsibilityV2LineageDigestEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_LINEAGE_DIGEST_EVENTS_EMPTY");
        }
        return PROFILE + "\n" + events.stream()
                .map(ResponsibilityV2LineageDigest::canonicalEvent)
                .collect(Collectors.joining("\n"));
    }

    public static String digest(
            List<ResponsibilityV2LineageDigestEvent> events) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical(events).getBytes(
                                    StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String canonicalEvent(
            ResponsibilityV2LineageDigestEvent event) {
        return event.lineageVersion()
                + "|" + event.eventId()
                + "|" + (event.supersedesId() == null
                        ? "-" : event.supersedesId())
                + "|" + event.sourceVersion()
                + "|" + event.sourceWatermark()
                + "|" + event.recordVersion()
                + "|" + event.payloadDigest()
                + "|" + event.changeKind()
                + "|" + event.reasonCode()
                + "|" + UTC_MICROSECOND.format(event.effectiveAt());
    }
}
