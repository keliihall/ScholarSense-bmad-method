package cn.edu.suda.scholarsense.identityaccess.domain;

public record RefreshRotation(IdentitySession session, boolean reuseDetected) {}
