package cn.edu.suda.scholarsense.runtime;

import java.util.Locale;

public enum RuntimeRole {
    WEB_API("web-api"),
    WORKER("worker");

    private final String wireName;

    RuntimeRole(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    static RuntimeRole parse(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (RuntimeRole role : values()) {
            if (role.wireName.equals(normalized)) {
                return role;
            }
        }
        throw new ConfigurationException(
                "CONFIG_UNKNOWN_ROLE", "SCHOLARSENSE_ROLE", "must be web-api or worker");
    }
}
