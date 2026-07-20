package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record ContinuationCreated(String continuationCode, Instant expiresAt) {}
