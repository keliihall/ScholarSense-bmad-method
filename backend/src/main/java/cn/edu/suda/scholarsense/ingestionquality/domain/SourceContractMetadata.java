package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.List;

public record SourceContractMetadata(
        String ownerDepartment,
        String ownerName,
        String businessDefinition,
        List<String> businessKeys,
        String updateFrequency,
        String slo,
        String coverage,
        String sensitivity,
        String reconciliation,
        int backfillWindowDays,
        boolean watermarkRequired,
        List<String> contractTests,
        String consumerMode) {
    public SourceContractMetadata {
        businessKeys = List.copyOf(businessKeys == null ? List.of() : businessKeys);
        contractTests = List.copyOf(contractTests == null ? List.of() : contractTests);
    }

    public static SourceContractMetadata unregistered() {
        return new SourceContractMetadata(
                "未登记", "未登记", "未登记", List.of(), "未登记", "未登记", "未登记",
                "未登记", "未登记", 0, false, List.of(), "unregistered");
    }
}
