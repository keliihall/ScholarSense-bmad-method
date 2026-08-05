package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgreSqlDataSourceStartupGateTest {
    private static final String PRODUCTION_URL =
            "jdbc:postgresql://db.example.invalid/scholarsense"
                    + "?sslmode=verify-full&channelBinding=require";

    @Test
    void verifiesExactServerVersionSessionIdentityAndExclusiveInheritedRoleMembership()
            throws Exception {
        Fixture online = fixture(Proof.online("scholarsense_web_prod"));
        Fixture relay = fixture(Proof.relay("scholarsense_worker_prod"));

        PostgreSqlConnectionProfile onlineProfile = PostgreSqlDataSourceStartupGate.verifyOnline(
                online.dataSource(), "prod", "scholarsense_web_prod");
        PostgreSqlConnectionProfile relayProfile = PostgreSqlDataSourceStartupGate.verifyRelay(
                relay.dataSource(), "prod", "scholarsense_worker_prod");

        assertEquals(PRODUCTION_URL, onlineProfile.jdbcUrl());
        assertEquals("scholarsense_web_prod", onlineProfile.expectedWorkloadIdentity());
        assertEquals("scholarsense_worker_prod", relayProfile.expectedWorkloadIdentity());
        verify(online.statement()).executeQuery(argThat(sql ->
                sql.contains("current_setting('server_version_num')")
                        && sql.contains("current_user")
                        && sql.contains("session_user")
                        && sql.contains("'MEMBER'")
                        && sql.contains("'USAGE'")
                        && sql.contains("'SET'")
                        && !sql.toLowerCase().contains("set role")));
        verify(online.statement()).executeQuery(argThat(sql ->
                sql.contains("expected_function")
                        && sql.contains("iq_add_catalog_source")
                        && sql.contains("iq_add_catalog_dependency")
                        && sql.contains("iq_record_catalog_validation")
                        && sql.contains("iq_publish_catalog")
                        && !sql.contains("iq_cleanup_expired")));
        verify(relay.statement()).executeQuery(argThat(sql ->
                sql.contains("expected_table") && sql.contains("'MAINTAIN'")
                        && sql.contains("iq_cleanup_expired")));
    }

    @Test
    void productionStartupFailsWithoutChannelBindingOrForAConnectionIdentityMismatch()
            throws Exception {
        Fixture missingBinding = fixture(
                Proof.online("scholarsense_web_prod"),
                "jdbc:postgresql://db.example.invalid/scholarsense?sslmode=verify-full");
        IllegalArgumentException channelBinding = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyOnline(
                        missingBinding.dataSource(), "prod", "scholarsense_web_prod"));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_CHANNEL_BINDING_REQUIRED",
                channelBinding.getMessage());

        Fixture wrongIdentity = fixture(Proof.online("unexpected_login"));
        IllegalArgumentException identity = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyOnline(
                        wrongIdentity.dataSource(), "prod", "scholarsense_web_prod"));
        assertEquals("INGESTION_QUALITY_DATABASE_IDENTITY_MISMATCH", identity.getMessage());
    }

    @Test
    void rejectsSetRoleSessionAndAnythingOtherThanPostgreSql180004() throws Exception {
        Fixture changedRole = fixture(new Proof(
                "scholarsense_ingestion_quality_relay",
                "scholarsense_worker_prod",
                "180004", true,
                false, false, false,
                true, true, false,
                true, true));
        IllegalArgumentException session = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyRelay(
                        changedRole.dataSource(), "prod",
                        "scholarsense_ingestion_quality_relay"));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_SESSION_IDENTITY_MISMATCH",
                session.getMessage());

        Fixture wrongVersion = fixture(Proof.online("scholarsense_web_prod").withVersion("180003"));
        IllegalArgumentException version = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyOnline(
                        wrongVersion.dataSource(), "prod", "scholarsense_web_prod"));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_SERVER_VERSION_MISMATCH",
                version.getMessage());
    }

    @Test
    void rejectsDangerousLoginOrGroupAttributesAndNonInheritedMembership() throws Exception {
        Fixture elevated = fixture(Proof.online("scholarsense_web_prod").withRestrictions(false));
        IllegalArgumentException restriction = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyOnline(
                        elevated.dataSource(), "prod", "scholarsense_web_prod"));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_ROLE_RESTRICTION_MISMATCH",
                restriction.getMessage());

        Fixture setRoleOnly = fixture(new Proof(
                "scholarsense_worker_prod", "scholarsense_worker_prod",
                "180004", true,
                false, false, false,
                true, true, true,
                true, true));
        IllegalArgumentException membership = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyRelay(
                        setRoleOnly.dataSource(), "prod", "scholarsense_worker_prod"));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH",
                membership.getMessage());

        Fixture onlineSetAllowed = fixture(
                Proof.online("scholarsense_web_prod").withSet(true, false));
        IllegalArgumentException onlineSet = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyOnline(
                        onlineSetAllowed.dataSource(), "prod", "scholarsense_web_prod"));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH",
                onlineSet.getMessage());
    }

    @Test
    void rejectsCrossMembershipAndAnyEffectiveTableOrColumnPrivilegeDrift()
            throws Exception {
        Fixture crossMember = fixture(new Proof(
                "scholarsense_worker_prod", "scholarsense_worker_prod",
                "180004", true,
                true, true, false,
                true, true, false,
                true, true));
        IllegalArgumentException membership = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyRelay(
                        crossMember.dataSource(), "prod", "scholarsense_worker_prod"));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH",
                membership.getMessage());

        Fixture drifted = fixture(Proof.relay("scholarsense_worker_prod").withMatrix(false));
        IllegalArgumentException privileges = assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlDataSourceStartupGate.verifyRelay(
                        drifted.dataSource(), "prod", "scholarsense_worker_prod"));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_PRIVILEGE_MATRIX_MISMATCH",
                privileges.getMessage());
    }

    private static Fixture fixture(Proof proof) throws Exception {
        return fixture(proof, PRODUCTION_URL);
    }

    private static Fixture fixture(Proof proof, String url) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet principal = mock(ResultSet.class);
        ResultSet matrix = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(url);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("expected_table")
                        ? matrix
                        : principal);
        when(principal.next()).thenReturn(true, false);
        when(principal.getString(1)).thenReturn(proof.currentUser());
        when(principal.getString(2)).thenReturn(proof.sessionUser());
        when(principal.getString(3)).thenReturn(proof.serverVersion());
        when(principal.getBoolean(4)).thenReturn(proof.restrictedLogin());
        when(principal.getBoolean(5)).thenReturn(proof.onlineMember());
        when(principal.getBoolean(6)).thenReturn(proof.onlineUsage());
        when(principal.getBoolean(7)).thenReturn(proof.onlineSet());
        when(principal.getBoolean(8)).thenReturn(proof.relayMember());
        when(principal.getBoolean(9)).thenReturn(proof.relayUsage());
        when(principal.getBoolean(10)).thenReturn(proof.relaySet());
        when(principal.getBoolean(11)).thenReturn(proof.restrictedGroups());
        when(matrix.next()).thenReturn(true, false);
        when(matrix.getBoolean(1)).thenReturn(proof.matrixValid());
        return new Fixture(dataSource, statement);
    }

    private record Fixture(DataSource dataSource, Statement statement) {}

    private record Proof(
            String currentUser,
            String sessionUser,
            String serverVersion,
            boolean restrictedLogin,
            boolean onlineMember,
            boolean onlineUsage,
            boolean onlineSet,
            boolean relayMember,
            boolean relayUsage,
            boolean relaySet,
            boolean restrictedGroups,
            boolean matrixValid) {
        private static Proof online(String identity) {
            return new Proof(identity, identity, "180004", true,
                    true, true, false, false, false, false, true, true);
        }

        private static Proof relay(String identity) {
            return new Proof(identity, identity, "180004", true,
                    false, false, false, true, true, false, true, true);
        }

        private Proof withVersion(String value) {
            return new Proof(currentUser, sessionUser, value, restrictedLogin,
                    onlineMember, onlineUsage, onlineSet,
                    relayMember, relayUsage, relaySet,
                    restrictedGroups, matrixValid);
        }

        private Proof withRestrictions(boolean value) {
            return new Proof(currentUser, sessionUser, serverVersion, value,
                    onlineMember, onlineUsage, onlineSet,
                    relayMember, relayUsage, relaySet,
                    value, matrixValid);
        }

        private Proof withMatrix(boolean value) {
            return new Proof(currentUser, sessionUser, serverVersion, restrictedLogin,
                    onlineMember, onlineUsage, onlineSet,
                    relayMember, relayUsage, relaySet,
                    restrictedGroups, value);
        }

        private Proof withSet(boolean newOnlineSet, boolean newRelaySet) {
            return new Proof(currentUser, sessionUser, serverVersion, restrictedLogin,
                    onlineMember, onlineUsage, newOnlineSet,
                    relayMember, relayUsage, newRelaySet,
                    restrictedGroups, matrixValid);
        }
    }
}
