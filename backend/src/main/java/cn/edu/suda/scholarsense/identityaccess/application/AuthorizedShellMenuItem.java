package cn.edu.suda.scholarsense.identityaccess.application;

public record AuthorizedShellMenuItem(
        String id,
        String label,
        String routeName,
        String providerState) {}
