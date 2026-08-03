package cn.edu.suda.scholarsense.identityaccess.application;

public record AuthorizedShellSurface(
        String surfaceId,
        String title,
        String routeName,
        String providerState) {}
