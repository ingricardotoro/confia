package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specs/audit-trail, scenario AT04 (session-endpoints-cookie-and-csrf design.md, decision 17): a
 * chain of entries whose detail JSON was written with {@link AuditJson}, through the real writer
 * and the real chaining trigger, verifies as intact with {@link AuditChainVerifier}; the stored
 * detail is the document the builder produced, special characters included. The property test of
 * the canonical serialization ({@link CanonicalSerializationCrossCheckIT}) keeps running unchanged
 * in the same suite.
 */
class AuditChainAfterAuditJsonIT extends CommittingPostgresIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aChainOfEntriesWrittenWithTheBuilderVerifiesAsIntact() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        String tricky = "quote \" backslash \\ newline \n tab \t accent á symbol 中";
        String[] documents = {
            AuditJson.object().number("consecutiveFailures", 3).number("delaySeconds", 30).toJson(),
            AuditJson.object().text("reason", "invalid-password").toJson(),
            AuditJson.object().bool("secondFactor", true).toJson(),
            AuditJson.object().instant("expiresAt", Instant.parse("2026-10-21T10:15:30.5Z"))
                    .number("supersededCount", Integer.MAX_VALUE).toJson(),
            AuditJson.object().text("note", tricky).bool("flag", false).toJson(),
        };

        for (String document : documents) {
            append(institutionId, document);
        }

        AuditChainVerification result = verifier().verifyChainOf(institutionId);
        assertThat(result).asInstanceOf(type(AuditChainVerification.Intact.class)).satisfies(intact -> {
            assertThat(intact.institutionId()).isEqualTo(institutionId);
            assertThat(intact.verifiedRows()).isEqualTo((long) documents.length);
        });
        String stored = transactionRunner().execute(contextOf(institutionId), () -> dsl.fetchOne("""
                select after_value::text from shared_audit_log
                where institution_id = ? order by id desc limit 1
                """, institutionId.value()).get(0, String.class));
        assertThat(JSON.readTree(stored).get("note").textValue()).isEqualTo(tricky);
    }

    private AuditChainVerifier verifier() {
        return new DefaultAuditChainVerifier(transactionRunner(), new JooqAuditLogReader(dsl),
                new CanonicalAuditRowSerializer());
    }

    private void append(InstitutionId institutionId, String afterValue) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            new JooqAuditLogWriter(dsl).append(new AuditEntry(institutionId.value(), null,
                    "system", "audit-json-chain-test", null, null, UUID.randomUUID(), null,
                    "test.audit_json", "test_entity", "entity-1", "success", null, afterValue,
                    null, null));
            return null;
        });
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
