package cn.edu.suda.scholarsense.ingestionquality.application;

public record CatalogAuditRelayResult(int claimed, int delivered, int retried, int failed, int fenced) {}
