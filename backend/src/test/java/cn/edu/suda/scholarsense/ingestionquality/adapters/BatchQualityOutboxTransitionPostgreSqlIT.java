package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * PostgreSQL 18.4 contract for the batch-quality business outbox delivery boundary.
 *
 * <p>Administrator mutations exercise the trigger as a defence-in-depth boundary. Relay delivery
 * is intentionally available only through attempt-fenced, database-clocked SECURITY DEFINER
 * routines; no workload identity has raw UPDATE authority over the outbox.
 */
class BatchQualityOutboxTransitionPostgreSqlIT {
    private static final String TABLE = "ingestion_quality.iq_batch_quality_outbox";
    private static final String TRANSITION_INVALID =
            "INGESTION_QUALITY_BATCH_OUTBOX_TRANSITION_INVALID";
    private static final String EVENT_TYPE =
            "scholarsense.ingestion-quality.data-batch.assessed.v1";
    private static final String SCHEMA_VERSION = "DATA-BATCH-ASSESSED-1.0.0";
    private static final byte[] PAYLOAD =
            "{\"contract\":\"batch-quality-outbox-transition-red\"}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final String PAYLOAD_DIGEST = sha256(PAYLOAD);
    private static final String RETRY_ERROR = "BATCH_QUALITY_RELAY_UNAVAILABLE";
    private static final String TERMINAL_ERROR =
            "BATCH_QUALITY_PAYLOAD_INTEGRITY_INVALID";
    private static final String EXHAUSTED_ERROR =
            "BATCH_QUALITY_RELAY_ATTEMPTS_EXHAUSTED";
    private static final String UNKNOWN_ERROR = "BATCH_QUALITY_STUDENT_20260001";
    private static final long FIXED_LEASE_SECONDS = 300L;
    private static final long MAX_BACKOFF_SECONDS = 3_600L;
    private static final long MAX_ATTEMPTS = 8L;

    private static final String BATCH_OWNER_ROLE =
            "scholarsense_ingestion_quality_batch_owner";
    private static final String RELAY_ROLE = "scholarsense_ingestion_quality_relay";
    private static final String WORKER_ROLE =
            "scholarsense_ingestion_quality_quality_worker";
    private static final String ONLINE_ROLE = "scholarsense_ingestion_quality_online";
    private static final String RETENTION_ROLE =
            "scholarsense_ingestion_quality_retention_executor";
    private static final String AUTHORITY_ROLE =
            "scholarsense_ingestion_quality_consumer_registry_authority";

    private static final String RELAY_LOGIN =
            "scholarsense_iq_outbox_relay_test_login";
    private static final String WORKER_LOGIN =
            "scholarsense_iq_outbox_worker_test_login";
    private static final String ONLINE_LOGIN =
            "scholarsense_iq_outbox_online_test_login";
    private static final String RETENTION_LOGIN =
            "scholarsense_iq_outbox_retention_test_login";
    private static final String AUTHORITY_LOGIN =
            "scholarsense_iq_outbox_authority_test_login";

    private static final String CLAIM_FUNCTION =
            "iq_claim_next_batch_quality_outbox()";
    private static final String RELEASE_FUNCTION =
            "iq_release_batch_quality_outbox(uuid,bigint)";
    private static final String DELIVER_FUNCTION =
            "iq_deliver_batch_quality_outbox(uuid,bigint)";
    private static final String FAIL_FUNCTION =
            "iq_fail_batch_quality_outbox(uuid,bigint)";
    private static final List<String> FENCED_FUNCTIONS = List.of(
            CLAIM_FUNCTION, RELEASE_FUNCTION, DELIVER_FUNCTION, FAIL_FUNCTION);
    private static final List<WorkloadLogin> WORKLOAD_LOGINS = List.of(
            new WorkloadLogin(RELAY_ROLE, RELAY_LOGIN),
            new WorkloadLogin(WORKER_ROLE, WORKER_LOGIN),
            new WorkloadLogin(ONLINE_ROLE, ONLINE_LOGIN),
            new WorkloadLogin(RETENTION_ROLE, RETENTION_LOGIN),
            new WorkloadLogin(AUTHORITY_ROLE, AUTHORITY_LOGIN));

    @Test
    void illegalDeliveryStateShapesAreRejectedByTheDatabaseTrigger() {
        JdbcTemplate jdbc = admin();
        UUID directDelivery = event(1);
        UUID pendingLease = event(2);
        UUID retryWithDeliveryTime = event(3);
        UUID deliveryWithoutTime = event(4);
        UUID failedWithoutError = event(5);
        UUID failedWithUncontrolledError = event(6);
        UUID retryWithoutLeaseOrError = event(7);
        UUID retryWithLeaseAndError = event(8);
        UUID failedWithUnknownVocabularyCode = event(9);
        for (UUID eventId : List.of(
                directDelivery,
                pendingLease,
                retryWithDeliveryTime,
                deliveryWithoutTime,
                failedWithoutError,
                failedWithUncontrolledError,
                retryWithoutLeaseOrError,
                retryWithLeaseAndError,
                failedWithUnknownVocabularyCode)) {
            seedPending(jdbc, eventId);
        }
        claim(jdbc, retryWithDeliveryTime);
        claim(jdbc, deliveryWithoutTime);
        claim(jdbc, failedWithoutError);
        claim(jdbc, failedWithUncontrolledError);
        claim(jdbc, failedWithUnknownVocabularyCode);

        assertAll(
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='delivered'
                         where event_id=?
                        """, directDelivery)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set claimed_until=statement_timestamp() + interval '10 minutes'
                         where event_id=?
                        """, pendingLease)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set delivered_at=statement_timestamp()
                         where event_id=?
                        """, retryWithDeliveryTime)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='delivered', claimed_until=null,
                               delivered_at=null, last_error_code=null
                         where event_id=?
                        """, deliveryWithoutTime)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='failed', claimed_until=null,
                               delivered_at=null, last_error_code=null
                         where event_id=?
                        """, failedWithoutError)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='failed', claimed_until=null, delivered_at=null,
                               last_error_code='broker exposed student-20260001'
                         where event_id=?
                        """, failedWithUncontrolledError)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='failed', claimed_until=null, delivered_at=null,
                               last_error_code=?
                         where event_id=?
                        """, UNKNOWN_ERROR, failedWithUnknownVocabularyCode)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='retrying', attempts=1,
                               claimed_until=null, delivered_at=null,
                               last_error_code=null
                         where event_id=?
                        """, retryWithoutLeaseOrError)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='retrying', attempts=1,
                               claimed_until=statement_timestamp() + interval '10 minutes',
                               delivered_at=null, last_error_code=?
                         where event_id=?
                        """, RETRY_ERROR, retryWithLeaseAndError)));
    }

    @Test
    void attemptsAreMonotoneAndAdvanceExactlyOnceOnlyWhenClaiming() {
        JdbcTemplate jdbc = admin();
        UUID regression = event(20);
        UUID initialSkip = event(21);
        UUID releaseSkip = event(22);
        UUID deliverySkip = event(23);
        for (UUID eventId : List.of(regression, initialSkip, releaseSkip, deliverySkip)) {
            seedPending(jdbc, eventId);
        }
        claim(jdbc, regression);
        claim(jdbc, releaseSkip);
        claim(jdbc, deliverySkip);

        assertAll(
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set attempts=0
                         where event_id=?
                        """, regression)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='retrying', attempts=2,
                               claimed_until=statement_timestamp() + interval '10 minutes',
                               delivered_at=null, last_error_code=null
                         where event_id=?
                        """, initialSkip)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set attempts=attempts + 1,
                               available_at=statement_timestamp(), claimed_until=null,
                               delivered_at=null, last_error_code=?
                         where event_id=?
                        """, RETRY_ERROR, releaseSkip)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='delivered', attempts=attempts + 1,
                               claimed_until=null, delivered_at=statement_timestamp(),
                               last_error_code=null
                         where event_id=?
                        """, deliverySkip)));
    }

    @Test
    void deliveredAndFailedRowsAreTerminal() {
        JdbcTemplate jdbc = admin();
        UUID delivered = event(30);
        UUID failed = event(31);
        UUID deliveredToFailed = event(32);
        UUID failedToDelivered = event(33);
        for (UUID eventId : List.of(delivered, failed, deliveredToFailed, failedToDelivered)) {
            seedPending(jdbc, eventId);
            claim(jdbc, eventId);
        }
        deliver(jdbc, delivered);
        failClaimed(jdbc, failed);
        deliver(jdbc, deliveredToFailed);
        failClaimed(jdbc, failedToDelivered);

        assertAll(
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='retrying', attempts=attempts + 1,
                               claimed_until=statement_timestamp() + interval '10 minutes',
                               delivered_at=null, last_error_code=null
                         where event_id=?
                        """, delivered)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='retrying', attempts=attempts + 1,
                               claimed_until=statement_timestamp() + interval '10 minutes',
                               delivered_at=null, last_error_code=null
                         where event_id=?
                        """, failed)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='failed', delivered_at=null,
                               last_error_code=?, claimed_until=null
                         where event_id=?
                        """, TERMINAL_ERROR, deliveredToFailed)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        update ingestion_quality.iq_batch_quality_outbox
                           set status='delivered', delivered_at=statement_timestamp(),
                               last_error_code=null, claimed_until=null
                         where event_id=?
                        """, failedToDelivered)));
    }

    @Test
    void legalClaimReleaseReclaimDeliveryAndFailurePathsRemainAvailable() {
        JdbcTemplate jdbc = admin();
        UUID deliveredEvent = event(40);
        seedPending(jdbc, deliveredEvent);

        assertState(state(jdbc, deliveredEvent), "pending", 0L, false, false, null);
        claim(jdbc, deliveredEvent);
        DeliveryState firstClaim = state(jdbc, deliveredEvent);
        assertState(firstClaim, "retrying", 1L, true, false, null);
        assertTrue(firstClaim.claimedUntil().isAfter(Instant.now()));

        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set status='retrying', available_at=statement_timestamp(),
                       claimed_until=null, delivered_at=null, last_error_code=?
                 where event_id=?
                """, RETRY_ERROR, deliveredEvent));
        assertState(state(jdbc, deliveredEvent), "retrying", 1L, false, false, RETRY_ERROR);

        claim(jdbc, deliveredEvent);
        DeliveryState secondClaim = state(jdbc, deliveredEvent);
        assertState(secondClaim, "retrying", 2L, true, false, null);
        deliver(jdbc, deliveredEvent);
        DeliveryState delivered = state(jdbc, deliveredEvent);
        assertState(delivered, "delivered", 2L, false, true, null);
        assertNotNull(delivered.deliveredAt());

        UUID failedAfterClaim = event(41);
        seedPending(jdbc, failedAfterClaim);
        claim(jdbc, failedAfterClaim);
        failClaimed(jdbc, failedAfterClaim);
        assertState(state(jdbc, failedAfterClaim),
                "failed", 1L, false, false, TERMINAL_ERROR);

        UUID integrityFailure = event(42);
        seedPending(jdbc, integrityFailure);
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set status='failed', attempts=attempts + 1,
                       available_at=statement_timestamp(), claimed_until=null,
                       delivered_at=null, last_error_code=?
                 where event_id=?
                """, TERMINAL_ERROR, integrityFailure));
        assertState(state(jdbc, integrityFailure),
                "failed", 1L, false, false, TERMINAL_ERROR);
    }

    @Test
    void fencedRoutinesAreClosedOwnedAndGrantedOnlyToTheRelayWorkload() {
        JdbcTemplate jdbc = admin();
        ensureWorkloadLogins(jdbc);

        assertEquals(BATCH_OWNER_ROLE, jdbc.queryForObject("""
                select pg_catalog.pg_get_userbyid(relowner)
                  from pg_catalog.pg_class
                 where oid=?::regclass
                """, String.class, TABLE));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
                select rolcanlogin
                  from pg_catalog.pg_roles
                 where rolname=?
                """, Boolean.class, BATCH_OWNER_ROLE)));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
                select coalesce(bool_or(
                           privilege.grantee=0
                           and privilege.privilege_type='USAGE'), false)
                  from pg_catalog.pg_namespace namespace
                  cross join lateral pg_catalog.aclexplode(coalesce(
                      namespace.nspacl,
                      pg_catalog.acldefault('n', namespace.nspowner))) privilege
                 where namespace.nspname='ingestion_quality'
                """, Boolean.class)));
        assertEquals(0L, jdbc.queryForObject("""
                select count(*)
                  from information_schema.table_privileges
                 where table_schema='ingestion_quality'
                   and table_name='iq_batch_quality_outbox'
                   and grantee='PUBLIC'
                """, Long.class));

        List<String> allColumns = jdbc.queryForList("""
                select column_name
                  from information_schema.columns
                 where table_schema='ingestion_quality'
                   and table_name='iq_batch_quality_outbox'
                """, String.class);
        assertFalse(allColumns.isEmpty());
        for (WorkloadLogin workload : WORKLOAD_LOGINS) {
            assertEquals(RELAY_ROLE.equals(workload.role()), Boolean.TRUE.equals(
                    jdbc.queryForObject("select has_table_privilege(?, ?, 'SELECT')",
                            Boolean.class, workload.role(), TABLE)),
                    workload.role() + " SELECT privilege drift");
            for (String column : allColumns) {
                assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                                "select has_column_privilege(?, ?, ?, 'UPDATE')",
                                Boolean.class, workload.role(), TABLE, column)),
                        workload.role() + " unexpectedly updates " + column);
            }
        }

        for (String function : FENCED_FUNCTIONS) {
            FunctionContract contract = functionContract(jdbc, function);
            assertEquals(BATCH_OWNER_ROLE, contract.owner(), function);
            assertTrue(contract.securityDefiner(), function);
            assertEquals("search_path=pg_catalog", contract.settings(), function);
            assertEquals("v", contract.volatility(), function);
            assertEquals(!CLAIM_FUNCTION.equals(function), contract.strict(), function);
            assertEquals(Set.of(BATCH_OWNER_ROLE, RELAY_ROLE),
                    functionExecuteGrantees(jdbc, function), function);
            for (WorkloadLogin workload : WORKLOAD_LOGINS) {
                assertEquals(RELAY_ROLE.equals(workload.role()), Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "select has_function_privilege(?, ?, 'EXECUTE')",
                                Boolean.class, workload.role(), qualified(function))),
                        workload.role() + " EXECUTE drift on " + function);
            }
        }

        for (WorkloadLogin workload : WORKLOAD_LOGINS) {
            JdbcTemplate actualLogin = workload(workload.login());
            assertEquals(workload.login(), actualLogin.queryForObject(
                    "select session_user", String.class));
            assertEquals(workload.login(), actualLogin.queryForObject(
                    "select current_user", String.class));
            assertEquals(Set.of(workload.role() + "|true|false|false"),
                    directMemberships(jdbc, workload.login()));
            assertPermissionDenied(() -> actualLogin.update("""
                    update ingestion_quality.iq_batch_quality_outbox
                       set status='pending'
                     where false
                    """));
            if (!RELAY_ROLE.equals(workload.role())) {
                assertPermissionDenied(() -> actualLogin.queryForList("""
                        select event_id, attempts, claimed_until
                          from ingestion_quality.iq_claim_next_batch_quality_outbox()
                        """));
                assertPermissionDenied(() -> actualLogin.queryForObject("""
                        select ingestion_quality.iq_release_batch_quality_outbox(?, ?)
                        """, Boolean.class, event(50), 1L));
            }
        }
    }

    @Test
    void relayUsesDatabaseClockedClaimReleaseReclaimDeliveryAndFailurePaths() {
        JdbcTemplate jdbc = admin();
        clearFencedFixtures(jdbc);
        ensureWorkloadLogins(jdbc);
        JdbcTemplate relay = workload(RELAY_LOGIN);
        UUID deliveredEvent = event(80);
        seedPendingAt(jdbc, deliveredEvent, Instant.parse("1900-01-01T00:00:00Z"));

        Instant firstClaimBefore = databaseNow(jdbc);
        OutboxClaim firstClaim = claimNext(relay);
        Instant firstClaimAfter = databaseNow(jdbc);
        assertEquals(deliveredEvent, firstClaim.eventId());
        assertEquals(1L, firstClaim.attempts());
        assertBetween(firstClaim.claimedUntil(),
                firstClaimBefore.plusSeconds(FIXED_LEASE_SECONDS),
                firstClaimAfter.plusSeconds(FIXED_LEASE_SECONDS));
        assertState(state(jdbc, deliveredEvent), "retrying", 1L, true, false, null);

        Instant releaseBefore = databaseNow(jdbc);
        assertTrue(release(relay, deliveredEvent, firstClaim.attempts()));
        Instant releaseAfter = databaseNow(jdbc);
        DeliveryState released = state(jdbc, deliveredEvent);
        assertState(released, "retrying", 1L, false, false, RETRY_ERROR);
        assertBetween(released.availableAt(), releaseBefore,
                releaseAfter.plusSeconds(MAX_BACKOFF_SECONDS));

        makeReleasedEventDueForReclaim(jdbc, deliveredEvent);
        OutboxClaim secondClaim = claimNext(relay);
        assertEquals(deliveredEvent, secondClaim.eventId());
        assertEquals(2L, secondClaim.attempts());
        assertTrue(deliver(relay, deliveredEvent, secondClaim.attempts()));
        assertState(state(jdbc, deliveredEvent), "delivered", 2L, false, true, null);

        UUID failedEvent = event(81);
        seedPendingAt(jdbc, failedEvent, Instant.parse("1900-01-02T00:00:00Z"));
        OutboxClaim failedClaim = claimNext(relay);
        assertEquals(failedEvent, failedClaim.eventId());
        assertEquals(1L, failedClaim.attempts());
        assertTrue(fail(relay, failedEvent, failedClaim.attempts()));
        assertState(state(jdbc, failedEvent),
                "failed", 1L, false, false, TERMINAL_ERROR);
    }

    @Test
    void staleClaimantCannotFinalizeAReclaimedAttempt() {
        JdbcTemplate jdbc = admin();
        clearFencedFixtures(jdbc);
        ensureWorkloadLogins(jdbc);
        JdbcTemplate relay = workload(RELAY_LOGIN);
        UUID eventId = event(90);
        seedPendingAt(jdbc, eventId, Instant.parse("1899-01-01T00:00:00Z"));

        OutboxClaim claimantA = claimNext(relay);
        assertEquals(eventId, claimantA.eventId());
        assertEquals(1L, claimantA.attempts());
        expireLeaseForReclaim(jdbc, eventId);
        OutboxClaim claimantB = claimNext(relay);
        assertEquals(eventId, claimantB.eventId());
        assertEquals(2L, claimantB.attempts());
        DeliveryState bOwnsLiveClaim = state(jdbc, eventId);

        assertAll(
                () -> assertFalse(release(relay, eventId, claimantA.attempts())),
                () -> assertFalse(deliver(relay, eventId, claimantA.attempts())),
                () -> assertFalse(fail(relay, eventId, claimantA.attempts())));
        assertEquals(bOwnsLiveClaim, state(jdbc, eventId));

        assertTrue(deliver(relay, eventId, claimantB.attempts()));
        assertState(state(jdbc, eventId), "delivered", 2L, false, true, null);
    }

    @Test
    void relayCannotForgeBoundsErrorsOrAttemptsAndExhaustionIsTerminal() {
        JdbcTemplate jdbc = admin();
        clearFencedFixtures(jdbc);
        ensureWorkloadLogins(jdbc);
        JdbcTemplate relay = workload(RELAY_LOGIN);
        UUID exhaustedEvent = event(100);
        seedPendingAt(jdbc, exhaustedEvent, Instant.parse("1898-01-01T00:00:00Z"));
        OutboxClaim initialClaim = claimNext(relay);
        assertEquals(exhaustedEvent, initialClaim.eventId());
        forceLiveAttemptForExhaustion(jdbc, exhaustedEvent, MAX_ATTEMPTS);

        assertTrue(release(relay, exhaustedEvent, MAX_ATTEMPTS));
        assertState(state(jdbc, exhaustedEvent),
                "failed", MAX_ATTEMPTS, false, false, EXHAUSTED_ERROR);
        assertFalse(release(relay, exhaustedEvent, MAX_ATTEMPTS));
        assertFalse(deliver(relay, exhaustedEvent, MAX_ATTEMPTS));
        assertFalse(fail(relay, exhaustedEvent, MAX_ATTEMPTS));

        UUID crashedAtLimit = event(102);
        seedPendingAt(jdbc, crashedAtLimit, Instant.parse("1897-01-01T00:00:00Z"));
        OutboxClaim crashClaim = claimNext(relay);
        assertEquals(crashedAtLimit, crashClaim.eventId());
        forceExpiredAttemptForExhaustion(jdbc, crashedAtLimit, MAX_ATTEMPTS);
        relay.queryForList("""
                select event_id, attempts, claimed_until
                  from ingestion_quality.iq_claim_next_batch_quality_outbox()
                """);
        assertState(state(jdbc, crashedAtLimit),
                "failed", MAX_ATTEMPTS, false, false, EXHAUSTED_ERROR);

        UUID boundedEvent = event(101);
        seedPendingAt(jdbc, boundedEvent, Instant.parse("1898-01-02T00:00:00Z"));
        OutboxClaim boundedClaim = claimNext(relay);
        assertEquals(boundedEvent, boundedClaim.eventId());
        DeliveryState databaseClaim = state(jdbc, boundedEvent);
        assertTrue(databaseClaim.attempts() <= MAX_ATTEMPTS);
        assertPermissionDenied(() -> relay.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set claimed_until='9999-12-31T23:59:59Z'
                 where event_id=?
                """, boundedEvent));
        assertPermissionDenied(() -> relay.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set status='retrying', claimed_until=null,
                       last_error_code=?, available_at='9999-12-31T23:59:59Z'
                 where event_id=?
                """, UNKNOWN_ERROR, boundedEvent));
        assertFalse(deliver(relay, boundedEvent, Long.MAX_VALUE));
        assertEquals(databaseClaim, state(jdbc, boundedEvent));
        assertTrue(deliver(relay, boundedEvent, boundedClaim.attempts()));
        assertState(state(jdbc, boundedEvent), "delivered", 1L, false, true, null);
    }

    @Test
    void insertRequiresAPristinePendingShapeAndRejectsPrecompletedRows() {
        JdbcTemplate jdbc = admin();
        UUID preclaimed = event(60);
        UUID dirtyPending = event(61);
        UUID precompleted = event(62);

        assertAll(
                () -> assertTransitionRejected(() -> jdbc.update("""
                        insert into ingestion_quality.iq_batch_quality_outbox
                          (event_id, aggregate_id, aggregate_version, event_type,
                           schema_version, payload_utf8, payload_digest, status, attempts,
                           available_at, claimed_until, delivered_at, last_error_code,
                           created_at)
                        values (?, ?, 1, ?, ?, ?, ?, 'retrying', 1,
                                statement_timestamp() - interval '1 minute',
                                statement_timestamp() + interval '10 minutes',
                                null, null, statement_timestamp())
                        """, preclaimed, preclaimed, EVENT_TYPE, SCHEMA_VERSION,
                        PAYLOAD, PAYLOAD_DIGEST)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        insert into ingestion_quality.iq_batch_quality_outbox
                          (event_id, aggregate_id, aggregate_version, event_type,
                           schema_version, payload_utf8, payload_digest, status, attempts,
                           available_at, claimed_until, delivered_at, last_error_code,
                           created_at)
                        values (?, ?, 1, ?, ?, ?, ?, 'pending', 1,
                                statement_timestamp(), null, null, null,
                                statement_timestamp())
                        """, dirtyPending, dirtyPending, EVENT_TYPE, SCHEMA_VERSION,
                        PAYLOAD, PAYLOAD_DIGEST)),
                () -> assertTransitionRejected(() -> jdbc.update("""
                        insert into ingestion_quality.iq_batch_quality_outbox
                          (event_id, aggregate_id, aggregate_version, event_type,
                           schema_version, payload_utf8, payload_digest, status, attempts,
                           available_at, claimed_until, delivered_at, last_error_code,
                           created_at)
                        values (?, ?, 1, ?, ?, ?, ?, 'delivered', 1,
                                statement_timestamp(), null, statement_timestamp(), null,
                                statement_timestamp())
                        """, precompleted, precompleted, EVENT_TYPE, SCHEMA_VERSION,
                        PAYLOAD, PAYLOAD_DIGEST)));
    }

    @Test
    void administratorCannotRewriteTheImmutableEventEnvelope() {
        JdbcTemplate jdbc = admin();
        UUID eventId = event(70);
        seedPending(jdbc, eventId);
        byte[] changedPayload = "{\"changed\":true}".getBytes(StandardCharsets.UTF_8);

        DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set payload_utf8=?, payload_digest=?
                 where event_id=?
                """, changedPayload, sha256(changedPayload), eventId));
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.contains("INGESTION_QUALITY_BATCH_OUTBOX_IMMUTABLE"), message);
    }

    private static void seedPending(JdbcTemplate jdbc, UUID eventId) {
        assertEquals(1, jdbc.update("""
                insert into ingestion_quality.iq_batch_quality_outbox
                  (event_id, aggregate_id, aggregate_version, event_type, schema_version,
                   payload_utf8, payload_digest, status, attempts, available_at,
                   claimed_until, delivered_at, last_error_code, created_at)
                values (?, ?, 1, ?, ?, ?, ?, 'pending', 0,
                        statement_timestamp() - interval '1 minute',
                        null, null, null, statement_timestamp())
                """, eventId, eventId, EVENT_TYPE, SCHEMA_VERSION, PAYLOAD, PAYLOAD_DIGEST));
    }

    private static void seedPendingAt(
            JdbcTemplate jdbc, UUID eventId, Instant availableAt) {
        assertEquals(1, jdbc.update("""
                insert into ingestion_quality.iq_batch_quality_outbox
                  (event_id, aggregate_id, aggregate_version, event_type, schema_version,
                   payload_utf8, payload_digest, status, attempts, available_at,
                   claimed_until, delivered_at, last_error_code, created_at)
                values (?, ?, 1, ?, ?, ?, ?, 'pending', 0, ?,
                        null, null, null, statement_timestamp())
                """, eventId, eventId, EVENT_TYPE, SCHEMA_VERSION, PAYLOAD, PAYLOAD_DIGEST,
                Timestamp.from(availableAt)));
    }

    private static void clearFencedFixtures(JdbcTemplate jdbc) {
        jdbc.update("""
                delete from ingestion_quality.iq_batch_quality_outbox
                 where event_id::text like '019fe570-0000-7000-8000-%'
                   and event_type=?
                   and schema_version=?
                   and payload_digest=?
                """, EVENT_TYPE, SCHEMA_VERSION, PAYLOAD_DIGEST);
    }

    private static OutboxClaim claimNext(JdbcTemplate relay) {
        OutboxClaim claim = relay.queryForObject("""
                select event_id, attempts, claimed_until
                  from ingestion_quality.iq_claim_next_batch_quality_outbox()
                """, (row, ignored) -> new OutboxClaim(
                        row.getObject("event_id", UUID.class),
                        row.getLong("attempts"),
                        instant(row.getTimestamp("claimed_until"))));
        assertNotNull(claim);
        assertNotNull(claim.eventId());
        assertNotNull(claim.claimedUntil());
        return claim;
    }

    private static boolean release(
            JdbcTemplate relay, UUID eventId, long expectedAttempt) {
        return Boolean.TRUE.equals(relay.queryForObject("""
                select ingestion_quality.iq_release_batch_quality_outbox(?, ?)
                """, Boolean.class, eventId, expectedAttempt));
    }

    private static boolean deliver(
            JdbcTemplate relay, UUID eventId, long expectedAttempt) {
        return Boolean.TRUE.equals(relay.queryForObject("""
                select ingestion_quality.iq_deliver_batch_quality_outbox(?, ?)
                """, Boolean.class, eventId, expectedAttempt));
    }

    private static boolean fail(
            JdbcTemplate relay, UUID eventId, long expectedAttempt) {
        return Boolean.TRUE.equals(relay.queryForObject("""
                select ingestion_quality.iq_fail_batch_quality_outbox(?, ?)
                """, Boolean.class, eventId, expectedAttempt));
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        Timestamp timestamp = jdbc.queryForObject(
                "select statement_timestamp()", Timestamp.class);
        assertNotNull(timestamp);
        return timestamp.toInstant();
    }

    private static void assertBetween(
            Instant actual, Instant inclusiveLower, Instant inclusiveUpper) {
        assertNotNull(actual);
        assertFalse(actual.isBefore(inclusiveLower),
                () -> actual + " before lower bound " + inclusiveLower);
        assertFalse(actual.isAfter(inclusiveUpper),
                () -> actual + " after upper bound " + inclusiveUpper);
    }

    private static FunctionContract functionContract(
            JdbcTemplate jdbc, String function) {
        FunctionContract contract = jdbc.queryForObject("""
                select pg_catalog.pg_get_userbyid(proc.proowner) as owner,
                       proc.prosecdef as security_definer,
                       proc.proisstrict as strict,
                       proc.provolatile::text as volatility,
                       coalesce(array_to_string(proc.proconfig, ','), '') as settings
                  from pg_catalog.pg_proc proc
                 where proc.oid=pg_catalog.to_regprocedure(?)
                """, (row, ignored) -> new FunctionContract(
                        row.getString("owner"),
                        row.getBoolean("security_definer"),
                        row.getBoolean("strict"),
                        row.getString("volatility"),
                        row.getString("settings")), qualified(function));
        assertNotNull(contract);
        return contract;
    }

    private static Set<String> functionExecuteGrantees(
            JdbcTemplate jdbc, String function) {
        return Set.copyOf(jdbc.queryForList("""
                select case when privilege.grantee=0 then 'PUBLIC'
                            else pg_catalog.pg_get_userbyid(privilege.grantee) end
                  from pg_catalog.pg_proc proc
                  cross join lateral pg_catalog.aclexplode(coalesce(
                      proc.proacl,
                      pg_catalog.acldefault('f', proc.proowner))) privilege
                 where proc.oid=pg_catalog.to_regprocedure(?)
                   and privilege.privilege_type='EXECUTE'
                """, String.class, qualified(function)));
    }

    private static Set<String> directMemberships(
            JdbcTemplate jdbc, String login) {
        return Set.copyOf(jdbc.queryForList("""
                select parent.rolname || '|' || membership.inherit_option::text
                       || '|' || membership.set_option::text
                       || '|' || membership.admin_option::text
                  from pg_catalog.pg_auth_members membership
                  join pg_catalog.pg_roles parent on parent.oid=membership.roleid
                  join pg_catalog.pg_roles member on member.oid=membership.member
                 where member.rolname=?
                """, String.class, login));
    }

    private static String qualified(String function) {
        return "ingestion_quality." + function;
    }

    /*
     * These three administrator-only fixture operations move database-clocked state to the shape
     * it would have after lease/backoff passage. They bypass only the row trigger, never the relay
     * surface under test, so the suite does not sleep for a five-minute production lease.
     */
    private static void makeReleasedEventDueForReclaim(
            JdbcTemplate jdbc, UUID eventId) {
        mutateControlStateWithoutTrigger(jdbc, """
                update ingestion_quality.iq_batch_quality_outbox
                   set available_at=statement_timestamp() - interval '1 second'
                 where event_id=?
                """, eventId, null);
    }

    private static void expireLeaseForReclaim(
            JdbcTemplate jdbc, UUID eventId) {
        mutateControlStateWithoutTrigger(jdbc, """
                update ingestion_quality.iq_batch_quality_outbox
                   set claimed_until=statement_timestamp() - interval '1 second'
                 where event_id=?
                """, eventId, null);
    }

    private static void forceLiveAttemptForExhaustion(
            JdbcTemplate jdbc, UUID eventId, long attempts) {
        mutateControlStateWithoutTrigger(jdbc, """
                update ingestion_quality.iq_batch_quality_outbox
                   set attempts=?,
                       claimed_until=statement_timestamp() + interval '5 minutes'
                 where event_id=?
                """, eventId, attempts);
    }

    private static void forceExpiredAttemptForExhaustion(
            JdbcTemplate jdbc, UUID eventId, long attempts) {
        mutateControlStateWithoutTrigger(jdbc, """
                update ingestion_quality.iq_batch_quality_outbox
                   set attempts=?,
                       claimed_until=statement_timestamp() - interval '1 second'
                 where event_id=?
                """, eventId, attempts);
    }

    private static void mutateControlStateWithoutTrigger(
            JdbcTemplate jdbc, String sql, UUID eventId, Long attempts) {
        DataSource source = jdbc.getDataSource();
        assertNotNull(source);
        try (Connection connection = source.getConnection();
                Statement session = connection.createStatement()) {
            session.execute("set session_replication_role=replica");
            try (PreparedStatement mutation = connection.prepareStatement(sql)) {
                if (attempts == null) {
                    mutation.setObject(1, eventId);
                } else {
                    mutation.setLong(1, attempts);
                    mutation.setObject(2, eventId);
                }
                assertEquals(1, mutation.executeUpdate());
            } finally {
                session.execute("set session_replication_role=origin");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot advance outbox fixture clock", failure);
        }
    }

    private static void claim(JdbcTemplate jdbc, UUID eventId) {
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set status='retrying', attempts=attempts + 1,
                       claimed_until=statement_timestamp() + interval '10 minutes',
                       delivered_at=null, last_error_code=null
                 where event_id=?
                """, eventId));
    }

    private static void deliver(JdbcTemplate jdbc, UUID eventId) {
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set status='delivered', claimed_until=null,
                       delivered_at=statement_timestamp(), last_error_code=null
                 where event_id=?
                """, eventId));
    }

    private static void failClaimed(JdbcTemplate jdbc, UUID eventId) {
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set status='failed', available_at=statement_timestamp(),
                       claimed_until=null, delivered_at=null, last_error_code=?
                 where event_id=?
                """, TERMINAL_ERROR, eventId));
    }

    private static DeliveryState state(JdbcTemplate jdbc, UUID eventId) {
        DeliveryState result = jdbc.queryForObject("""
                select status, attempts, available_at, claimed_until,
                       delivered_at, last_error_code
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, (row, ignored) -> new DeliveryState(
                        row.getString("status"),
                        row.getLong("attempts"),
                        instant(row.getTimestamp("available_at")),
                        instant(row.getTimestamp("claimed_until")),
                        instant(row.getTimestamp("delivered_at")),
                        row.getString("last_error_code")), eventId);
        assertNotNull(result);
        return result;
    }

    private static void assertState(
            DeliveryState actual,
            String status,
            long attempts,
            boolean claimed,
            boolean delivered,
            String error) {
        assertEquals(status, actual.status());
        assertEquals(attempts, actual.attempts());
        assertNotNull(actual.availableAt());
        if (claimed) assertNotNull(actual.claimedUntil());
        else assertNull(actual.claimedUntil());
        if (delivered) assertNotNull(actual.deliveredAt());
        else assertNull(actual.deliveredAt());
        assertEquals(error, actual.lastErrorCode());
    }

    private static void assertTransitionRejected(Runnable mutation) {
        DataAccessException failure = assertThrows(DataAccessException.class, mutation::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.contains(TRANSITION_INVALID), message);
    }

    private static void assertPermissionDenied(Runnable mutation) {
        DataAccessException failure = assertThrows(DataAccessException.class, mutation::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.toLowerCase().contains("permission denied"), message);
    }

    private static void ensureWorkloadLogins(JdbcTemplate jdbc) {
        jdbc.execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_outbox_relay_test_login') then
                        create role scholarsense_iq_outbox_relay_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_outbox_worker_test_login') then
                        create role scholarsense_iq_outbox_worker_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_outbox_online_test_login') then
                        create role scholarsense_iq_outbox_online_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_outbox_retention_test_login') then
                        create role scholarsense_iq_outbox_retention_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_outbox_authority_test_login') then
                        create role scholarsense_iq_outbox_authority_test_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_outbox_relay_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_outbox_worker_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_outbox_online_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_outbox_retention_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_outbox_authority_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_consumer_registry_authority,
                       scholarsense_ingestion_quality_batch_owner
                    from scholarsense_iq_outbox_relay_test_login,
                         scholarsense_iq_outbox_worker_test_login,
                         scholarsense_iq_outbox_online_test_login,
                         scholarsense_iq_outbox_retention_test_login,
                         scholarsense_iq_outbox_authority_test_login;
                grant scholarsense_ingestion_quality_relay
                    to scholarsense_iq_outbox_relay_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_quality_worker
                    to scholarsense_iq_outbox_worker_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_online
                    to scholarsense_iq_outbox_online_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_retention_executor
                    to scholarsense_iq_outbox_retention_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_consumer_registry_authority
                    to scholarsense_iq_outbox_authority_test_login
                    with inherit true, set false;
                """);
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(dataSource(required("scholarsense.audit.pg.user")));
    }

    private static JdbcTemplate workload(String username) {
        return new JdbcTemplate(dataSource(username));
    }

    private static DataSource dataSource(String username) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(username);
        source.setPassword(System.getProperty("scholarsense.audit.pg.password", ""));
        return source;
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }

    private static UUID event(int suffix) {
        return UUID.fromString("019fe570-0000-7000-8000-%012d".formatted(suffix));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record WorkloadLogin(String role, String login) {}

    private record FunctionContract(
            String owner,
            boolean securityDefiner,
            boolean strict,
            String volatility,
            String settings) {}

    private record OutboxClaim(UUID eventId, long attempts, Instant claimedUntil) {}

    private record DeliveryState(
            String status,
            long attempts,
            Instant availableAt,
            Instant claimedUntil,
            Instant deliveredAt,
            String lastErrorCode) {}
}
