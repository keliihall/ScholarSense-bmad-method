package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/** One non-authorizing RuleVersion/window classification in a recovery preview. */
public record RecoveryWindowImpact(
        String ruleVersion,
        String windowDigest,
        Instant latestActionableAt,
        Category category) {

    public RecoveryWindowImpact {
        ruleVersion = ruleVersion(ruleVersion);
        windowDigest = RecoveryVersionBindings.digest(windowDigest);
        latestActionableAt = microsecond(latestActionableAt);
        category = Objects.requireNonNull(category);
    }

    static RecoveryWindowImpact classify(
            Input input, Instant trustedServerNow, Instant expectedObservationCompletesAt) {
        Objects.requireNonNull(input);
        Instant now = microsecond(trustedServerNow);
        Instant completesAt = microsecond(expectedObservationCompletesAt);
        if (!completesAt.isAfter(now)) throw invalid();

        Category result;
        if (input.latestActionableAt().isBefore(now)) {
            result = Category.ALREADY_EXPIRED_HISTORY_ONLY;
        } else if (input.latestActionableAt().isBefore(completesAt)) {
            result = Category.EXPECTED_TO_EXPIRE_BEFORE_OBSERVATION_COMPLETES;
        } else {
            result = Category.CURRENTLY_POTENTIALLY_ACTIONABLE;
        }
        return new RecoveryWindowImpact(
                input.ruleVersion(), input.windowDigest(), input.latestActionableAt(), result);
    }

    public record Input(String ruleVersion, String windowDigest, Instant latestActionableAt) {
        public Input {
            ruleVersion = RecoveryWindowImpact.ruleVersion(ruleVersion);
            windowDigest = RecoveryVersionBindings.digest(windowDigest);
            latestActionableAt = microsecond(latestActionableAt);
        }
    }

    public enum Category {
        ALREADY_EXPIRED_HISTORY_ONLY("already-expired-history-only"),
        CURRENTLY_POTENTIALLY_ACTIONABLE("currently-potentially-actionable"),
        EXPECTED_TO_EXPIRE_BEFORE_OBSERVATION_COMPLETES(
                "expected-to-expire-before-observation-completes");

        private final String wireValue;

        Category(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    static Instant microsecond(Instant value) {
        Instant required = Objects.requireNonNull(value);
        int year;
        try {
            year = required.atOffset(ZoneOffset.UTC).getYear();
        } catch (RuntimeException error) {
            throw invalid();
        }
        if (required.getNano() % 1_000 != 0 || year < 1 || year > 9_999) throw invalid();
        return required;
    }

    private static String ruleVersion(String value) {
        if (value == null
                || !value.matches("^[A-Z0-9][A-Z0-9_-]{1,63}@[0-9A-Za-z][0-9A-Za-z._+\\-]{0,63}$")) {
            throw invalid();
        }
        return value;
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_WINDOW_IMPACT_INVALID");
    }
}
