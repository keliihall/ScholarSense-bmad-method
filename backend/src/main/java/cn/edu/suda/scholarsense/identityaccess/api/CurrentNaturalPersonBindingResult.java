package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Comparator;
import java.util.List;

public record CurrentNaturalPersonBindingResult(
        Availability availability,
        List<CurrentNaturalPersonBinding> bindings,
        String bindingSetDigest,
        String reasonCode,
        String traceId) {
    public enum Availability { AVAILABLE, UNAVAILABLE, NOT_INSTALLED }
    public CurrentNaturalPersonBindingResult {
        bindings = List.copyOf(bindings).stream()
                .sorted(Comparator.comparing(CurrentNaturalPersonBinding::businessOwnerKeyDigest))
                .toList();
    }
}
