package cn.edu.suda.scholarsense.ingestionquality.application;

@FunctionalInterface
public interface CatalogAuditPort {
    void append(CatalogAuditEvent event);
}
