package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

public record QualityEligibilityQuarantine(
        UUID eventId, String sourceId, String reasonCode, String payloadDigest) {}
