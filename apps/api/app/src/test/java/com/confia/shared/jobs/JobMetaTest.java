package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The metadata half of the task envelope (design.md decision 6; scenario BJ10). */
class JobMetaTest {

    private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Test
    void keepsEveryFieldItWasGiven() {
        JobMeta meta = new JobMeta(CORRELATION, REQUEST, ACTOR, ActorKind.STAFF, INSTITUTION, "00-trace-01");

        assertThat(meta.correlationId()).isEqualTo(CORRELATION);
        assertThat(meta.requestId()).isEqualTo(REQUEST);
        assertThat(meta.actorId()).isEqualTo(ACTOR);
        assertThat(meta.actorKind()).isEqualTo(ActorKind.STAFF);
        assertThat(meta.institutionId()).isEqualTo(INSTITUTION);
        assertThat(meta.traceparent()).isEqualTo("00-trace-01");
    }

    @Test
    void theActorAndTheTraceparentMayBeAbsent() {
        JobMeta meta = new JobMeta(CORRELATION, REQUEST, null, ActorKind.STAFF, INSTITUTION, null);

        assertThat(meta.actorId()).isNull();
        assertThat(meta.traceparent()).isNull();
    }

    @Test
    void everyOtherFieldIsRequired() {
        assertThatThrownBy(() -> new JobMeta(null, REQUEST, ACTOR, ActorKind.STAFF, INSTITUTION, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> new JobMeta(CORRELATION, null, ACTOR, ActorKind.STAFF, INSTITUTION, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("requestId");
        assertThatThrownBy(() -> new JobMeta(CORRELATION, REQUEST, ACTOR, null, INSTITUTION, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("actorKind");
        assertThatThrownBy(() -> new JobMeta(CORRELATION, REQUEST, ACTOR, ActorKind.STAFF, null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("institutionId");
    }

    @Test
    void theEnvelopeRequiresItsTwoParts() {
        JobMeta meta = new JobMeta(CORRELATION, REQUEST, null, ActorKind.SYSTEM, INSTITUTION, null);

        assertThat(new TracedJob<>("payload", meta).meta()).isSameAs(meta);
        assertThatThrownBy(() -> new TracedJob<>(null, meta)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
        assertThatThrownBy(() -> new TracedJob<>("payload", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("meta");
    }
}
