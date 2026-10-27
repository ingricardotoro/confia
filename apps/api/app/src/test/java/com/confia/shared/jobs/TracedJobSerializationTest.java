package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.jobs.infrastructure.JobDataRejectedException;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The strict serializer of {@code task_data} (background-jobs-with-db-scheduler design.md decision
 * 8; scenarios BJ10, BJ11, BJ53, BJ54 and BJ55). The attacks come first: a Java-serialized object, an
 * unknown field, a document over 4 KiB and an error message that could repeat the data.
 */
class TracedJobSerializationTest {

    enum Kind { FIRST, SECOND }

    record SamplePayload(UUID accountId, Instant at, LocalDate day, boolean flag, int count, long total,
            Kind kind) { }

    record CountPayload(int count) { }

    private static final String TASK = "identity.some-job";
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000a7");
    private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final String DISTINCTIVE = "SECRET-VALUE-7f3a9c";

    private final TracedJobSerializer serializer = new TracedJobSerializer();

    private static JobMeta meta(String traceparent) {
        return new JobMeta(CORRELATION, REQUEST, ACTOR, ActorKind.STAFF, INSTITUTION, traceparent);
    }

    private static SamplePayload payload() {
        return new SamplePayload(ACCOUNT, Instant.parse("2026-10-25T09:30:00Z"), LocalDate.parse("2026-10-25"),
                true, 7, 9_000_000_000L, Kind.SECOND);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    // --- Shape and round trip -------------------------------------------------------------------

    @Test
    void writesTheDocumentWithPayloadAndMetaInUtf8AndIsoDates() {
        byte[] document = serializer.serialize(TASK, new TracedJob<>(payload(), meta("00-abc-01")));

        assertThat(text(document)).isEqualTo("{\"payload\":{\"accountId\":\"" + ACCOUNT
                + "\",\"at\":\"2026-10-25T09:30:00Z\",\"day\":\"2026-10-25\",\"flag\":true,\"count\":7,"
                + "\"total\":9000000000,\"kind\":\"SECOND\"},\"meta\":{\"correlationId\":\"" + CORRELATION
                + "\",\"requestId\":\"" + REQUEST + "\",\"actorId\":\"" + ACTOR
                + "\",\"actorKind\":\"STAFF\",\"institutionId\":\"" + INSTITUTION
                + "\",\"traceparent\":\"00-abc-01\"}}");
    }

    @Test
    void anAbsentActorAndTraceparentAreWrittenAsNull() {
        JobMeta anonymous = new JobMeta(CORRELATION, REQUEST, null, ActorKind.STAFF, INSTITUTION, null);

        String document = text(serializer.serialize(TASK, new TracedJob<>(new CountPayload(1), anonymous)));

        assertThat(document).contains("\"actorId\":null").contains("\"traceparent\":null");
    }

    @Test
    void aPayloadAndItsMetaMakeARoundTrip() {
        TracedJob<SamplePayload> original = new TracedJob<>(payload(), meta("00-abc-01"));

        TracedJob<?> read = serializer.deserialize(TASK, serializer.serialize(TASK, original));

        assertThat(read.meta()).isEqualTo(original.meta());
        assertThat(serializer.payloadOf(TASK, SamplePayload.class, read)).isEqualTo(original.payload());
    }

    @Test
    void anAbsentActorSurvivesTheRoundTripAsNull() {
        JobMeta anonymous = new JobMeta(CORRELATION, REQUEST, null, ActorKind.GUARDIAN, INSTITUTION, null);

        TracedJob<?> read = serializer.deserialize(TASK,
                serializer.serialize(TASK, new TracedJob<>(new CountPayload(1), anonymous)));

        assertThat(read.meta()).isEqualTo(anonymous);
    }

    @Test
    void theLibraryEntryPointsAcceptOnlyTheEnvelope() {
        Serializer asLibrary = serializer;
        TracedJob<CountPayload> job = new TracedJob<>(new CountPayload(3), meta(null));

        byte[] document = asLibrary.serialize(job);
        TracedJob<?> read = asLibrary.deserialize(TracedJob.class, document);

        assertThat(read.meta()).isEqualTo(job.meta());
        assertThat(serializer.payloadOf(TASK, CountPayload.class, read)).isEqualTo(job.payload());
    }

    @Test
    void aPayloadThatAlreadyHasTheTargetTypeIsReturnedAsItIs() {
        CountPayload payload = new CountPayload(5);

        assertThat(serializer.payloadOf(TASK, CountPayload.class, new TracedJob<>(payload, meta(null))))
                .isSameAs(payload);
    }

    // --- Java serialization is refused by construction (BJ11) ------------------------------------

    static final AtomicBoolean INSTANTIATED = new AtomicBoolean();

    static final class Trap implements Serializable {
        private static final long serialVersionUID = 1L;

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            INSTANTIATED.set(true);
            in.defaultReadObject();
        }
    }

    @Test
    void aJavaSerializedObjectIsRejectedWithoutInstantiatingAnything() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(new Trap());
        }
        byte[] javaBytes = buffer.toByteArray();
        assertThat(javaBytes[0] & 0xFF).isEqualTo(0xAC);
        assertThat(javaBytes[1] & 0xFF).isEqualTo(0xED);
        INSTANTIATED.set(false);

        assertThatThrownBy(() -> serializer.deserialize(TASK, javaBytes)).isInstanceOf(JobDataRejectedException.class)
                .hasMessageContaining(TASK);
        assertThatThrownBy(() -> serializer.deserialize(TracedJob.class, javaBytes))
                .isInstanceOf(JobDataRejectedException.class);
        assertThat(INSTANTIATED).isFalse();
    }

    @Test
    void theSerializerRefusesToWriteAnythingButTheEnvelope() {
        assertThatThrownBy(() -> serializer.serialize("a string")).isInstanceOf(JobDataRejectedException.class);
        assertThatThrownBy(() -> serializer.serialize(new HashMap<String, String>()))
                .isInstanceOf(JobDataRejectedException.class);
        assertThatThrownBy(() -> serializer.serialize(new Trap())).isInstanceOf(JobDataRejectedException.class);
        assertThat(serializer.serialize((Object) null)).as("a task without data stores no bytes").isNull();
        assertThat(serializer.deserialize(Void.class, null)).as("and reads back as no data").isNull();
    }

    @Test
    void theSerializerRefusesToReadAnythingButTheEnvelope() {
        byte[] document = serializer.serialize(TASK, new TracedJob<>(new CountPayload(1), meta(null)));

        assertThatThrownBy(() -> serializer.deserialize(String.class, document))
                .isInstanceOf(JobDataRejectedException.class);
        assertThatThrownBy(() -> serializer.deserialize(Object.class, document))
                .isInstanceOf(JobDataRejectedException.class);
    }

    @Test
    void onlyAJsonObjectIsReadAndNothingMayFollowIt() {
        String valid = text(serializer.serialize(TASK, new TracedJob<>(new CountPayload(1), meta(null))));

        for (String notAnObject : new String[] {"", "[]", "[1,2]", "\"text\"", "123", "null", " " + valid,
                valid + valid, valid + " x"}) {
            assertThatThrownBy(() -> serializer.deserialize(TASK, bytes(notAnObject)))
                    .as("document: '%s'", notAnObject.length() > 20 ? notAnObject.substring(0, 20) : notAnObject)
                    .isInstanceOf(JobDataRejectedException.class);
        }
        assertThatThrownBy(() -> serializer.deserialize(TASK, null)).isInstanceOf(JobDataRejectedException.class);
    }

    // --- Unknown, missing and mistyped fields (BJ54, BJ18) ---------------------------------------

    private static String envelope(String payload, String meta) {
        return "{\"payload\":" + payload + ",\"meta\":" + meta + "}";
    }

    private static String validMeta() {
        return "{\"correlationId\":\"" + CORRELATION + "\",\"requestId\":\"" + REQUEST + "\",\"actorId\":null,"
                + "\"actorKind\":\"STAFF\",\"institutionId\":\"" + INSTITUTION + "\",\"traceparent\":null}";
    }

    @Test
    void anUnknownFieldInMetaIsRejectedWhenTheDocumentIsRead() {
        String meta = validMeta().replace("\"traceparent\":null", "\"traceparent\":null,\"extra\":\"x\"");

        assertThatThrownBy(() -> serializer.deserialize(TASK, bytes(envelope("{\"count\":1}", meta))))
                .isInstanceOf(JobDataRejectedException.class).hasMessageContaining(TASK);
    }

    @Test
    void anUnknownFieldInTheEnvelopeIsRejected() {
        String document = "{\"payload\":{\"count\":1},\"meta\":" + validMeta() + ",\"extra\":1}";

        assertThatThrownBy(() -> serializer.deserialize(TASK, bytes(document)))
                .isInstanceOf(JobDataRejectedException.class);
    }

    @Test
    void anUnknownFieldInThePayloadIsRejectedWhenThePayloadIsConverted() {
        TracedJob<?> read = serializer.deserialize(TASK,
                bytes(envelope("{\"count\":1,\"extra\":\"x\"}", validMeta())));

        assertThatThrownBy(() -> serializer.payloadOf(TASK, CountPayload.class, read))
                .isInstanceOf(JobDataRejectedException.class).hasMessageContaining(TASK);
    }

    @Test
    void aMissingInstitutionMakesTheEnvelopeInvalid() {
        String meta = validMeta().replace(",\"institutionId\":\"" + INSTITUTION + "\"", "");

        assertThatThrownBy(() -> serializer.deserialize(TASK, bytes(envelope("{\"count\":1}", meta))))
                .isInstanceOf(JobDataRejectedException.class);
    }

    @Test
    void aMissingPayloadOrMetaMakesTheEnvelopeInvalid() {
        assertThatThrownBy(() -> serializer.deserialize(TASK, bytes("{\"meta\":" + validMeta() + "}")))
                .isInstanceOf(JobDataRejectedException.class);
        assertThatThrownBy(() -> serializer.deserialize(TASK, bytes("{\"payload\":{\"count\":1}}")))
                .isInstanceOf(JobDataRejectedException.class);
    }

    @Test
    void aNullForAPrimitiveComponentIsRejected() {
        TracedJob<?> read = serializer.deserialize(TASK, bytes(envelope("{\"count\":null}", validMeta())));

        assertThatThrownBy(() -> serializer.payloadOf(TASK, CountPayload.class, read))
                .isInstanceOf(JobDataRejectedException.class);
    }

    @Test
    void aPayloadThatIsNotAnObjectIsRejectedWhenConverted() {
        TracedJob<?> read = serializer.deserialize(TASK, bytes(envelope("\"text\"", validMeta())));

        assertThatThrownBy(() -> serializer.payloadOf(TASK, CountPayload.class, read))
                .isInstanceOf(JobDataRejectedException.class);
    }

    // --- The size limit (BJ53) -------------------------------------------------------------------

    private static TracedJob<CountPayload> padded(int traceparentLength) {
        return new TracedJob<>(new CountPayload(1), meta("t".repeat(traceparentLength)));
    }

    @Test
    void aDocumentOfExactlyFourKibIsWrittenAndOneByteMoreIsRejected() {
        int base = serializer.serialize(TASK, padded(0)).length;
        int exactly = TracedJobSerializer.MAX_DOCUMENT_BYTES - base;

        assertThat(serializer.serialize(TASK, padded(exactly))).hasSize(TracedJobSerializer.MAX_DOCUMENT_BYTES);
        assertThatThrownBy(() -> serializer.serialize(TASK, padded(exactly + 1)))
                .isInstanceOf(JobDataRejectedException.class).hasMessageContaining(TASK)
                .hasMessageContaining("4096");
    }

    @Test
    void aStoredDocumentOverFourKibIsRejectedWhenReadAndOneOfExactlyFourKibIsRead() {
        String base = envelope("{\"count\":1}", validMeta().replace("\"traceparent\":null", "\"traceparent\":\"\""));
        int padding = TracedJobSerializer.MAX_DOCUMENT_BYTES - base.length();
        String exactly = base.replace("\"traceparent\":\"\"", "\"traceparent\":\"" + "t".repeat(padding) + "\"");
        String oneMore = base.replace("\"traceparent\":\"\"", "\"traceparent\":\"" + "t".repeat(padding + 1) + "\"");

        assertThat(bytes(exactly)).hasSize(TracedJobSerializer.MAX_DOCUMENT_BYTES);
        assertThat(serializer.deserialize(TASK, bytes(exactly)).meta().traceparent()).hasSize(padding);
        assertThatThrownBy(() -> serializer.deserialize(TASK, bytes(oneMore)))
                .isInstanceOf(JobDataRejectedException.class).hasMessageContaining("4096");
    }

    @Test
    void theLimitIsFourKibNotFourThousand() {
        assertThat(TracedJobSerializer.MAX_DOCUMENT_BYTES).isEqualTo(4 * 1024);
    }

    @Test
    void theLimitCountsBytesNotCharacters() {
        int base = serializer.serialize(TASK, padded(0)).length;
        // A two-byte character: 2,048 of them fit in 4,096 bytes only if counted as bytes.
        int characters = (TracedJobSerializer.MAX_DOCUMENT_BYTES - base) / 2 + 1;
        TracedJob<CountPayload> wide = new TracedJob<>(new CountPayload(1), meta("é".repeat(characters)));

        assertThatThrownBy(() -> serializer.serialize(TASK, wide)).isInstanceOf(JobDataRejectedException.class);
    }

    // --- The message names the task and the class, never the data (BJ55) ---------------------------

    @Test
    void theMessageNamesTheTaskAndTheOriginalExceptionClassAndNothingElse() {
        String meta = validMeta().replace(CORRELATION.toString(), DISTINCTIVE);

        assertThatThrownBy(() -> serializer.deserialize(TASK, bytes(envelope("{\"count\":1}", meta))))
                .isInstanceOfSatisfying(JobDataRejectedException.class, e -> {
                    assertThat(e.getMessage()).contains("'" + TASK + "'").contains("tools.jackson.")
                            .contains("Exception").doesNotContain(DISTINCTIVE);
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getSuppressed()).isEmpty();
                    assertThat(e.toString()).doesNotContain(DISTINCTIVE);
                    assertThat(e.taskName()).isEqualTo(TASK);
                });
    }

    @Test
    void thePayloadConversionAlsoHidesTheData() {
        TracedJob<?> read = serializer.deserialize(TASK,
                bytes(envelope("{\"count\":\"" + DISTINCTIVE + "\"}", validMeta())));

        assertThatThrownBy(() -> serializer.payloadOf(TASK, CountPayload.class, read))
                .isInstanceOfSatisfying(JobDataRejectedException.class, e -> {
                    assertThat(e.getMessage()).contains(TASK).doesNotContain(DISTINCTIVE);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void theLibraryEntryPointCannotNameTheTaskItDoesNotKnow() {
        assertThatThrownBy(() -> serializer.deserialize(TracedJob.class, bytes("{}")))
                .isInstanceOfSatisfying(JobDataRejectedException.class,
                        e -> assertThat(e.taskName()).isEqualTo(TracedJobSerializer.UNRESOLVED_TASK));
    }

    @Test
    void theRejectionNamesTheReasonWhenThereIsNoOriginalException() {
        assertThatThrownBy(() -> serializer.deserialize(TASK, bytes("[1]")))
                .isInstanceOf(JobDataRejectedException.class).hasMessageContaining(TASK)
                .hasMessageContaining(JobDataRejectedException.NOT_A_JSON_OBJECT);
    }
}
