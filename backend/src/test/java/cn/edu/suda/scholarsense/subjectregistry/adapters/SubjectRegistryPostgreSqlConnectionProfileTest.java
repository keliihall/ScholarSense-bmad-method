package cn.edu.suda.scholarsense.subjectregistry.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SubjectRegistryPostgreSqlConnectionProfileTest {
    @Test
    void productionRequiresCertificateAndChannelBindingWithoutUrlCredentials() {
        var profile = SubjectRegistryPostgreSqlConnectionProfile.validate(
                "prod",
                "jdbc:postgresql://db.example.invalid/scholarsense"
                        + "?sslmode=verify-full&channelBinding=require",
                "subject_registry_prod", "subject_registry_prod");
        assertEquals("subject_registry_prod", profile.expectedWorkloadIdentity());

        assertEquals("SUBJECT_REGISTRY_DATABASE_CHANNEL_BINDING_REQUIRED",
                assertThrows(IllegalArgumentException.class, () ->
                        SubjectRegistryPostgreSqlConnectionProfile.validate(
                                "prod",
                                "jdbc:postgresql://db.example.invalid/scholarsense"
                                        + "?sslmode=verify-full",
                                "subject_registry_prod", "subject_registry_prod"))
                        .getMessage());
        assertEquals("SUBJECT_REGISTRY_DATABASE_PROFILE_INVALID",
                assertThrows(IllegalArgumentException.class, () ->
                        SubjectRegistryPostgreSqlConnectionProfile.validate(
                                "test",
                                "jdbc:postgresql://db.example.invalid/scholarsense?user=leak",
                                "subject_registry_test", "subject_registry_test"))
                        .getMessage());
    }
}
