package cn.edu.suda.scholarsense.identityaccess.application;

/** Supplies the current non-secret key-state version used by projection rechecks. */
@FunctionalInterface
public interface SensitiveFieldKeyStatePort {
    String currentStateVersion();
}
