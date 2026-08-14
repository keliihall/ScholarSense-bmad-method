package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;

public record CurrentNaturalPersonBinding(
        String businessOwnerKeyDigest,
        String naturalPersonPrincipalDigest,
        long bindingVersion,
        Instant effectiveFrom,
        Instant effectiveTo) {}
