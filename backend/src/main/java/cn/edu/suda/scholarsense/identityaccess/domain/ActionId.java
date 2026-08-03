package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;

public record ActionId(String value) {
    public ActionId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[a-z][a-z0-9.-]{2,127}") || value.contains("/")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ACTION_NOT_EXPANDED");
        }
    }

    public static ActionId of(String value) {
        return new ActionId(value);
    }
}
