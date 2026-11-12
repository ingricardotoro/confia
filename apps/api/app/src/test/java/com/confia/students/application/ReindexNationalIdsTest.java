package com.confia.students.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.domain.NationalId;
import com.confia.students.domain.Student;
import com.confia.support.JdbcStubTransactions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The re-index after a rotation of the blind-index key: only the rows of another version are recomputed,
 * in batches, each its own transaction with an audit entry that carries counts and nothing else; a rerun
 * finds nothing to do.
 */
class ReindexNationalIdsTest {

    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryStudents store = new InMemoryStudents();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);
    private final AuditLogWriter auditWriter = audit::add;
    private final InstitutionId institution = new InstitutionId(UUID.randomUUID());

    /** Recomputes to {@code v2:<plain>} where the plain text is what the fake ciphertext wraps. */
    private final NationalIdReindexer reindexer = new NationalIdReindexer() {
        @Override
        public String currentVersion() {
            return "v2";
        }

        @Override
        public String recompute(InstitutionId institutionId, UUID studentId, String encrypted) {
            return "v2:" + encrypted.substring(4, encrypted.length() - 1);
        }
    };

    private final ReindexNationalIds reindex = new ReindexNationalIds(runner, store.students, reindexer, auditWriter);

    private UUID arrange(String code, String plain, String index) {
        UUID id = UUID.randomUUID();
        Student student = Student.create(id, code, "Ana", "Mena", LocalDate.of(2012, 5, 20), "F",
                plain == null ? null : NationalId.of(plain), NOW);
        store.arrange(institution, student,
                plain == null ? null : new ProtectedNationalId("enc[" + plain + "]", index));
        return id;
    }

    private String indexOf(UUID id) {
        return store.rowsOf(institution).stream().filter(s -> s.student().id().equals(id)).findFirst()
                .orElseThrow().document().blindIndex();
    }

    @Test
    void onlyTheRowsOfAnotherVersionAreRecomputedAndTheOthersAreLeftAlone() {
        UUID old = arrange("E-000001", "AB12345678", "v1:old");
        UUID current = arrange("E-000002", "CD12345678", "v2:keep");
        UUID without = arrange("E-000003", null, null);

        long done = reindex.reindex(institution, UUID.randomUUID());

        assertThat(done).isEqualTo(1);
        assertThat(indexOf(old)).isEqualTo("v2:AB12345678");
        assertThat(indexOf(current)).isEqualTo("v2:keep");
        assertThat(store.rowsOf(institution).stream().filter(s -> s.student().id().equals(without)).findFirst()
                .orElseThrow().document()).isNull();
    }

    @Test
    void aSecondRunFindsNothingToDoAndWritesNoEntry() {
        arrange("E-000001", "AB12345678", "v1:old");
        reindex.reindex(institution, UUID.randomUUID());
        audit.clear();
        transactionCalls.clear();

        long again = reindex.reindex(institution, UUID.randomUUID());

        assertThat(again).isZero();
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).hasSize(1);
    }

    @Test
    void theRunIsSplitInBatchesEachInItsOwnTransactionAndEachWithOneAuditEntryOfCountsOnly() {
        int rows = ReindexNationalIds.BATCH_SIZE + 5;
        for (int i = 0; i < rows; i++) {
            arrange("E-%06d".formatted(i + 1), "ID%08d".formatted(i), "v1:old" + i);
        }
        UUID run = UUID.randomUUID();

        long done = reindex.reindex(institution, run);

        assertThat(done).isEqualTo(rows);
        assertThat(transactionCalls).hasSize(2);
        assertThat(audit).hasSize(2);
        assertThat(audit).allSatisfy(entry -> {
            assertThat(entry.action()).isEqualTo(ReindexNationalIds.ACTION);
            assertThat(entry.actorKind()).isEqualTo("system");
            assertThat(entry.institutionId()).isEqualTo(institution.value());
            assertThat(entry.requestId()).isEqualTo(run);
            assertThat(entry.afterValue()).contains("\"version\":\"v2\"")
                    .doesNotContain("ID0000").doesNotContain("enc[").doesNotContain("v1:old");
        });
        assertThat(audit.get(0).afterValue()).contains("\"recomputed\":" + ReindexNationalIds.BATCH_SIZE);
        assertThat(audit.get(1).afterValue()).contains("\"recomputed\":5");
        assertThat(store.rowsOf(institution)).allSatisfy(
                row -> assertThat(row.document().blindIndex()).startsWith("v2:"));
    }

    @Test
    void anInstitutionNeverTouchesTheRowsOfAnother() {
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        UUID mine = arrange("E-000001", "AB12345678", "v1:old");
        UUID id = UUID.randomUUID();
        store.arrange(other, Student.create(id, "E-000001", "Luis", "Paz", LocalDate.of(2012, 5, 20), "M",
                NationalId.of("ZZ12345678"), NOW), new ProtectedNationalId("enc[ZZ12345678]", "v1:theirs"));

        reindex.reindex(institution, UUID.randomUUID());

        assertThat(indexOf(mine)).startsWith("v2:");
        assertThat(store.rowsOf(other).getFirst().document().blindIndex()).isEqualTo("v1:theirs");
    }
}
