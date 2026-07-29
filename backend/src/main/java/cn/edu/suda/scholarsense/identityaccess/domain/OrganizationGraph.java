package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OrganizationGraph {
    private OrganizationGraph() {}

    public static void validate(List<OrganizationNode> nodes) {
        Map<String, OrganizationNode> byExternalRef = new HashMap<>();
        for (OrganizationNode node : List.copyOf(nodes)) {
            if (byExternalRef.put(node.externalRefDigest(), node) != null) {
                throw failure("IDENTITY_EXTERNAL_ID_DUPLICATE");
            }
            if (node.externalRefDigest().equals(node.parentExternalRefDigest())) {
                throw failure("IDENTITY_ORGANIZATION_SELF_PARENT");
            }
        }
        for (OrganizationNode node : nodes) {
            if (node.parentExternalRefDigest() != null
                    && !byExternalRef.containsKey(node.parentExternalRefDigest())) {
                throw failure("IDENTITY_ORGANIZATION_ORPHAN");
            }
        }
        Set<String> complete = new HashSet<>();
        for (OrganizationNode node : nodes) {
            Set<String> path = new HashSet<>();
            OrganizationNode cursor = node;
            while (cursor != null && !complete.contains(cursor.externalRefDigest())) {
                if (!path.add(cursor.externalRefDigest())) {
                    throw failure("IDENTITY_ORGANIZATION_CYCLE");
                }
                cursor = cursor.parentExternalRefDigest() == null
                        ? null : byExternalRef.get(cursor.parentExternalRefDigest());
            }
            complete.addAll(path);
        }
    }

    private static IdentityAccessException failure(String code) {
        return new IdentityAccessException(code, "authoritative organization data is invalid");
    }
}
