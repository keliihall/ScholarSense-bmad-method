package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PostgreSqlConnectionProfileTest {

    @Test
    void productionRequiresVerifyFullChannelBindingAndExpectedWorkloadIdentity() {
        PostgreSqlConnectionProfile profile = PostgreSqlConnectionProfile.validate(
                "prod",
                "jdbc:postgresql://db.example.invalid/scholarsense?sslmode=verify-full&channelBinding=require",
                "scholarsense_ingestion_quality_online",
                "scholarsense_ingestion_quality_online");
        assertEquals("prod", profile.environment());

        assertThrows(IllegalArgumentException.class, () -> PostgreSqlConnectionProfile.validate(
                "prod", "jdbc:postgresql://db.example.invalid/scholarsense?sslmode=verify-full",
                "scholarsense_ingestion_quality_online", "scholarsense_ingestion_quality_online"));
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlConnectionProfile.validate(
                "prod", "jdbc:postgresql://db.example.invalid/scholarsense?sslmode=verify-full&channelBinding=require",
                "unexpected_role", "scholarsense_ingestion_quality_online"));
    }

    @Test
    void nonProductionStillRejectsCredentialsAndMalformedPostgresqlUrls() {
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlConnectionProfile.validate(
                "test", "jdbc:postgresql://user:password@localhost/test",
                "test-role", "test-role"));
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlConnectionProfile.validate(
                "test", "jdbc:mysql://localhost/test", "test-role", "test-role"));
    }
}
