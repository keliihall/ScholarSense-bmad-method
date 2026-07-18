package cn.edu.suda.scholarsense.runtime;

import java.util.Locale;

public enum RuntimeEnvironment {
    DEV("dev"),
    TEST("test"),
    STAGE("stage"),
    PROD("prod");

    private final String wireName;

    RuntimeEnvironment(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    static RuntimeEnvironment parse(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (RuntimeEnvironment environment : values()) {
            if (environment.wireName.equals(normalized)) {
                return environment;
            }
        }
        throw new ConfigurationException(
                "CONFIG_UNKNOWN_ENVIRONMENT", "SCHOLARSENSE_ENV", "must be dev, test, stage, or prod");
    }
}
