package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.application.UpstreamQualityEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class StrictDataBatchQualityEventDecoderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StrictDataBatchQualityEventDecoder decoder =
            new StrictDataBatchQualityEventDecoder();

    @Test
    void decodesFrozenAssessedAndPublishedFixturesWithoutInventingQshmWireFields()
            throws IOException {
        UpstreamQualityEvent assessed = decoder.decode(Files.readAllBytes(fixture(
                "valid/data-batch-quality-assessed-v1.json")));
        UpstreamQualityEvent published = decoder.decode(Files.readAllBytes(fixture(
                "valid/data-batch-published-v1.json")));

        assertEquals(3, assessed.batchAggregateVersion());
        assertEquals(4, published.batchAggregateVersion());
        assertEquals(assessed.snapshotId(), published.snapshotId());
        assertEquals("QMDP-1.0.0", assessed.qmdpVersion());
    }

    @Test
    void rejectsMissingSnapshotUnknownKeysAndDuplicateJsonKeys() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(
                Files.readAllBytes(fixture(
                        "invalid/data-batch-quality-event-missing-snapshot.json"))));

        byte[] unknown = """
                {"specversion":"1.0","type":"x","source":"x","id":"x","subject":"x",
                "time":"x","datacontenttype":"application/json","traceparent":"x",
                "data":{},"studentId":"forbidden"}
                """.getBytes();
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(unknown));

        byte[] duplicate = "{\"id\":\"a\",\"id\":\"b\"}".getBytes();
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(duplicate));
    }

    @Test
    void rejectsCloudEventDataBatchAndSnapshotValueBindingDrift() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mutated(
                "valid/data-batch-quality-assessed-v1.json",
                root -> root.put("id", "019fe700-0000-7000-8000-000000000099"))));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mutated(
                "valid/data-batch-quality-assessed-v1.json",
                root -> ((ObjectNode) root.required("data")).put(
                        "aggregateId", "019fe700-0000-7000-8000-000000000099"))));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mutated(
                "valid/data-batch-quality-assessed-v1.json",
                root -> ((ObjectNode) root.required("data")
                        .required("qualitySnapshot")).put("watermark", "drifted"))));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mutated(
                "valid/data-batch-quality-assessed-v1.json",
                root -> root.put(
                        "type", "scholarsense.ingestion-quality.data-batch.published.v1"))));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mutated(
                "valid/data-batch-quality-assessed-v1.json",
                root -> root.put("subject",
                        "data-batch/019fe700-0000-7000-8000-000000000099"))));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mutated(
                "valid/data-batch-quality-assessed-v1.json",
                root -> root.put("traceparent",
                        "00-ffeeddccbbaa99887766554433221100-ffeeddccbbaa9988-01"))));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mutated(
                "valid/data-batch-quality-assessed-v1.json",
                root -> ((ObjectNode) root.required("data")).put("aggregateVersion", 4))));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mutated(
                "valid/data-batch-quality-assessed-v1.json",
                root -> ((ObjectNode) root.required("data")
                        .required("qualitySnapshot")).put(
                        "sourceSchemaDigest", "sha256:" + "f".repeat(64)))));
    }

    private static byte[] mutated(String relative, Consumer<ObjectNode> mutation)
            throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(Files.readAllBytes(fixture(relative)));
        mutation.accept(root);
        return JSON.writeValueAsBytes(root);
    }

    private static Path fixture(String relative) {
        return Path.of("..", "contracts", "events", "ingestion-quality", "fixtures")
                .resolve(relative);
    }
}
