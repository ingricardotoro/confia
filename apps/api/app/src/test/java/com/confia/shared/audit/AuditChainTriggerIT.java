package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * The hash chain the {@code shared_audit_log_chain()} trigger maintains (design.md decisions 4, 5
 * and 6; specs/audit-trail/spec.md, requirements "Cadena de hash por institución con registro
 * génesis" and "El encadenamiento se calcula en el disparador del motor, no en la aplicación").
 *
 * <p>This cut's migration ({@code V3__chain_shared_audit_log.sql}, task 3.3) does not exist yet:
 * every method here fails against PR B2a's schema alone, where {@code shared_audit_log.id},
 * {@code prev_hash} and {@code row_hash} stay {@code NOT NULL} but unpopulated by any trigger, so
 * every insert below that omits them fails with a not-null-violation instead of the assertions
 * below ever running.
 */
class AuditChainTriggerIT extends CommittingPostgresIntegrationTest {

    private static final byte[] GENESIS_PREV_HASH = new byte[32];
    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void firstRowOfAnInstitutionIsItsGenesisRecord() {
        UUID institutionId = UUID.randomUUID();

        AuditRow first = insertRow(institutionId, "actor one");

        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.prevHash())
                .as("the genesis row's prev_hash must be exactly 32 zero bytes")
                .isEqualTo(GENESIS_PREV_HASH);
        assertThat(first.rowHash()).hasSize(32);
    }

    @Test
    void secondRowChainsWithThePreviousRowOfTheSameInstitution() {
        UUID institutionId = UUID.randomUUID();

        AuditRow first = insertRow(institutionId, "actor one");
        AuditRow second = insertRow(institutionId, "actor two");

        assertThat(second.id()).isEqualTo(2L);
        assertThat(second.prevHash())
                .as("the second row's prev_hash must be exactly the first row's row_hash")
                .isEqualTo(first.rowHash());
    }

    @Test
    void twoInstitutionsMaintainIndependentChains() {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();

        AuditRow firstOfA = insertRow(institutionA, "actor A1");
        AuditRow firstOfB = insertRow(institutionB, "actor B1");
        AuditRow secondOfA = insertRow(institutionA, "actor A2");

        assertThat(firstOfB.prevHash())
                .as("institution B's own genesis is unaffected by institution A's chain")
                .isEqualTo(GENESIS_PREV_HASH);
        assertThat(secondOfA.prevHash())
                .as("institution A's second row chains only against A's own first row, never B's")
                .isEqualTo(firstOfA.rowHash())
                .isNotEqualTo(firstOfB.rowHash());
    }

    @Test
    void theTriggerOverwritesWhateverTheCallerPassedForIdPrevHashAndRowHash() {
        UUID institutionId = UUID.randomUUID();
        byte[] fakePrevHash = randomBytes(32);
        byte[] fakeRowHash = randomBytes(32);

        AuditRow stored = transactionRunner().execute(contextOf(institutionId), () -> {
            Record record = dsl.fetchOne("""
                    insert into shared_audit_log
                        (id, institution_id, actor_kind, actor_label, request_id, action,
                         entity_type, entity_id, outcome, prev_hash, row_hash)
                    values (?, ?, 'system', 'attacker-supplied actor', ?, 'test.action',
                        'test_entity', 'entity-1', 'success', ?, ?)
                    returning id, prev_hash, row_hash
                    """, 999L, institutionId, UUID.randomUUID(), fakePrevHash, fakeRowHash);
            return toAuditRow(record);
        });

        assertThat(stored.id())
                .as("the trigger must assign its own id, ignoring the caller's fake 999")
                .isEqualTo(1L);
        assertThat(stored.prevHash())
                .as("the trigger must assign its own prev_hash, ignoring the caller's fake value")
                .isEqualTo(GENESIS_PREV_HASH)
                .isNotEqualTo(fakePrevHash);
        assertThat(stored.rowHash())
                .as("the trigger must assign its own row_hash, ignoring the caller's fake value")
                .isNotEqualTo(fakeRowHash);
    }

    @Test
    void aDirectSqlInsertOutsideAnyUseCaseIsAlsoChained() throws Exception {
        UUID institutionId = UUID.randomUUID();
        insertRow(institutionId, "actor via use case");

        AuditRow direct;
        try (Connection connection = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement setContext = connection
                    .prepareStatement("select set_config('app.institution_id', ?, true)")) {
                setContext.setString(1, institutionId.toString());
                setContext.execute();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into shared_audit_log
                        (institution_id, actor_kind, actor_label, request_id, action, entity_type,
                         entity_id, outcome)
                    values (?, 'system', 'direct sql actor', ?, 'test.action', 'test_entity',
                        'entity-1', 'success')
                    returning id, prev_hash, row_hash
                    """)) {
                insert.setObject(1, institutionId);
                insert.setObject(2, UUID.randomUUID());
                try (ResultSet resultSet = insert.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    direct = new AuditRow(resultSet.getLong("id"), resultSet.getBytes("prev_hash"),
                            resultSet.getBytes("row_hash"));
                }
            }
            connection.commit();
        }

        assertThat(direct.id())
                .as("a raw SQL insert, with no Java use case involved, is still assigned id=2 by "
                        + "the trigger, chained after the first (use-case) row of this institution")
                .isEqualTo(2L);
        assertThat(direct.prevHash())
                .as("the trigger chains a direct SQL insert exactly like any other insert")
                .hasSize(32)
                .isNotEqualTo(GENESIS_PREV_HASH);
    }

    private AuditRow insertRow(UUID institutionId, String actorLabel) {
        return transactionRunner().execute(contextOf(institutionId), () -> {
            Record record = dsl.fetchOne("""
                    insert into shared_audit_log
                        (institution_id, actor_kind, actor_label, request_id, action, entity_type,
                         entity_id, outcome)
                    values (?, 'system', ?, ?, 'test.action', 'test_entity', 'entity-1', 'success')
                    returning id, prev_hash, row_hash
                    """, institutionId, actorLabel, UUID.randomUUID());
            return toAuditRow(record);
        });
    }

    private static AuditRow toAuditRow(Record record) {
        return new AuditRow(record.get("id", Long.class), record.get("prev_hash", byte[].class),
                record.get("row_hash", byte[].class));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }

    private record AuditRow(long id, byte[] prevHash, byte[] rowHash) {
    }
}
