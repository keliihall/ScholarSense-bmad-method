package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.List;

public record CurrentNaturalPersonBindingQuery(
        List<String> businessOwnerKeyDigests,
        Instant trustedAt,
        String traceId) {
    public CurrentNaturalPersonBindingQuery {
        businessOwnerKeyDigests = List.copyOf(businessOwnerKeyDigests).stream().sorted().toList();
    }
}
