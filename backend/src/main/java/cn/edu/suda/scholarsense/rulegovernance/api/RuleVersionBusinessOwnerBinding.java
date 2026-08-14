package cn.edu.suda.scholarsense.rulegovernance.api;

import java.time.Instant;

/** Opaque accountable owner key, never human-readable catalog text. */
public record RuleVersionBusinessOwnerBinding(
        String ruleVersionDigest,
        String businessOwnerKeyDigest,
        long bindingVersion,
        Instant effectiveFrom,
        Instant effectiveTo) {}
