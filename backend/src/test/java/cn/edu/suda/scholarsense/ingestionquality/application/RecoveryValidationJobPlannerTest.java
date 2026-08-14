package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityException;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationJob;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationJobBinding;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryValidationJobPlannerTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Test
    void sameKeyAndBodyReplaysTheOriginalJobWithoutCreatingAnotherOne() {
        FakeSubmissions submissions = new FakeSubmissions();
        RecoveryValidationJobPlanner planner = new RecoveryValidationJobPlanner(submissions);
        RecoveryValidationSubmission first = submission('1', 'a');
        RecoveryValidationSubmission replay = new RecoveryValidationSubmission(
                first.idempotencyKeyDigest(), first.inputDigest(), job('2', 'a'));

        RecoveryValidationSubmissionResult created = planner.submit(first);
        RecoveryValidationSubmissionResult repeated = planner.submit(replay);

        assertFalse(created.replayed());
        assertTrue(repeated.replayed());
        assertSame(created.job(), repeated.job());
        assertEquals(1, submissions.insertions);
    }

    @Test
    void sameKeyWithDifferentInputDigestFailsClosed() {
        FakeSubmissions submissions = new FakeSubmissions();
        RecoveryValidationJobPlanner planner = new RecoveryValidationJobPlanner(submissions);
        planner.submit(submission('1', 'a'));

        IngestionQualityException mismatch = assertThrows(IngestionQualityException.class,
                () -> planner.submit(submission('1', 'b')));

        assertEquals(IngestionQualityErrorCode.INGESTION_QUALITY_IDEMPOTENCY_MISMATCH.name(),
                mismatch.code());
        assertEquals(1, submissions.insertions);
    }

    @Test
    void insertRaceAlsoReplaysOnlyWhenThePersistedBodyMatches() {
        RecoveryValidationJob winner = job('9', 'a');
        FakeSubmissions submissions = new FakeSubmissions();
        submissions.raceWinner = winner;

        RecoveryValidationSubmissionResult result = new RecoveryValidationJobPlanner(submissions)
                .submit(submission('1', 'a'));

        assertTrue(result.replayed());
        assertSame(winner, result.job());
    }

    private static RecoveryValidationSubmission submission(char jobSuffix, char input) {
        return new RecoveryValidationSubmission(digest('f'), digest(input), job(jobSuffix, input));
    }

    private static RecoveryValidationJob job(char suffix, char input) {
        return RecoveryValidationJob.queued(
                uuid("019ff5a0-1000-7000-8000-00000000010" + suffix),
                new RecoveryValidationJobBinding(
                        uuid("019ff5a0-1000-7000-8000-000000000201"),
                        uuid("019ff5a0-1000-7000-8000-000000000202"),
                        uuid("019ff5a0-1000-7000-8000-000000000203"),
                        digest(input), "QRP-1.0.0", digest('2'), digest('3'), digest('4'),
                        digest('5'), digest('6'), "00112233445566778899aabbccddeeff"),
                NOW);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static final class FakeSubmissions implements RecoveryValidationSubmissionPort {
        private final Map<String, RecoveryValidationJob> jobs = new HashMap<>();
        private RecoveryValidationJob raceWinner;
        private int insertions;

        @Override
        public Optional<RecoveryValidationJob> findByIdempotencyKeyDigest(String digest) {
            return Optional.ofNullable(jobs.get(digest));
        }

        @Override
        public RecoveryValidationJob insertIfAbsent(
                String idempotencyKeyDigest, RecoveryValidationJob requested) {
            insertions++;
            if (raceWinner != null) {
                jobs.put(idempotencyKeyDigest, raceWinner);
                return raceWinner;
            }
            jobs.putIfAbsent(idempotencyKeyDigest, requested);
            return jobs.get(idempotencyKeyDigest);
        }
    }
}
