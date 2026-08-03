package cn.edu.suda.scholarsense.identityaccess.api;

/** Lazy server-owned value reference. It is never accepted from an HTTP payload. */
@FunctionalInterface
public interface ServerOwnedFieldValueReference {
    Object resolve();
}
