package cn.edu.suda.scholarsense.identityaccess.api;

/** Server-internal projection boundary. No generic HTTP projection endpoint may expose it. */
@FunctionalInterface
public interface FieldProjectionPort {
    FieldProjectionResult project(FieldProjectionRequest request);
}
