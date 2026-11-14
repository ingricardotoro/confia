package com.confia.students.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.crypto.BlindIndexKeys;
import com.confia.shared.crypto.BlindIndexService;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.infrastructure.JooqDataEncryptionKeyRepository;
import com.confia.shared.security.SecurityContext;
import com.confia.students.application.ProtectedNationalId;
import com.confia.students.application.ReindexNationalIds;
import com.confia.students.domain.DuplicateNationalIdException;
import com.confia.students.domain.NationalId;
import com.confia.students.domain.Student;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rotation of the blind-index key against a real PostgreSQL (docs/03-seguridad.md section 7.3): while two
 * versions are configured a duplicate is found under either; the re-index recomputes the rows of the old
 * version by decrypting them, in batches, idempotently; afterwards the unique index holds under the new one.
 */
class BlindIndexRotationIT extends CommittingPostgresIntegrationTest {

    private static final String DOCUMENT = "0801201201234";
    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");

    private final String keyV1 = randomKey();
    private final String keyV2 = randomKey();
    private final InstitutionId institution = new InstitutionId(UUID.randomUUID());
    private final ColumnEncryptionMasterKey masterKey = randomMasterKey();

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static ColumnEncryptionMasterKey randomMasterKey() {
        return ColumnEncryptionMasterKey.fromBase64(randomKey());
    }

    private ColumnEncryptionNationalIdProtector protectorWith(String current, Map<String, String> keys) {
        ColumnEncryptionService encryption = new ColumnEncryptionService(
                new JooqDataEncryptionKeyRepository(dsl, new AesGcmCipher(), masterKey), new AesGcmCipher());
        return new ColumnEncryptionNationalIdProtector(encryption,
                new BlindIndexService(BlindIndexKeys.of(current, keys)));
    }

    private SecurityContext context() {
        return new SecurityContext("", "system", institution.value().toString(), UUID.randomUUID().toString());
    }

    private UUID insert(JooqStudentRepository students, ColumnEncryptionNationalIdProtector protector, String code) {
        UUID id = UUID.randomUUID();
        Student student = Student.create(id, code, "Ana", "Mena", LocalDate.of(2012, 5, 20), "F",
                NationalId.of(DOCUMENT), NOW);
        transactionRunner().execute(context(), () -> {
            ProtectedNationalId document = protector.protect(institution, id, NationalId.of(DOCUMENT));
            students.insert(institution, student, document);
            return null;
        });
        return id;
    }

    private String storedIndex(UUID id) {
        return transactionRunner().execute(context(), () -> dsl.fetchValue(
                "select national_id_blind_index::text from students_student where institution_id = ? and id = ?",
                institution.value(), id).toString());
    }

    @Test
    void duringARotationADuplicateIsFoundUnderTheOldVersionThenTheReindexMovesTheRowAndTheNewVersionHoldsAlone() {
        JooqStudentRepository students = new JooqStudentRepository(dsl);
        ColumnEncryptionNationalIdProtector before = protectorWith("v1", Map.of("v1", keyV1));
        ColumnEncryptionNationalIdProtector rotating = protectorWith("v2", Map.of("v1", keyV1, "v2", keyV2));
        UUID first = insert(students, before, "E-000001");
        assertThat(storedIndex(first)).matches("v1:[0-9a-f]{64}");

        // The same document under the new current version would not collide in the unique index; the lookup
        // across versions refuses it.
        assertThatThrownBy(() -> insert(students, rotating, "E-000002")).isInstanceOf(DuplicateNationalIdException.class);

        List<AuditEntry> audit = new ArrayList<>();
        ReindexNationalIds reindex = new ReindexNationalIds(transactionRunner(), students, rotating, audit::add);
        assertThat(reindex.reindex(institution, UUID.randomUUID())).isEqualTo(1);

        String moved = storedIndex(first);
        assertThat(moved).matches("v2:[0-9a-f]{64}");
        assertThat(moved).isEqualTo(transactionRunner().execute(context(),
                () -> rotating.protect(institution, first, NationalId.of(DOCUMENT)).blindIndex()));
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo(ReindexNationalIds.ACTION);
            assertThat(entry.afterValue()).doesNotContain(DOCUMENT).doesNotContain(moved);
        });

        // Idempotent: nothing is left to recompute, and no new entry.
        assertThat(reindex.reindex(institution, UUID.randomUUID())).isZero();
        assertThat(audit).hasSize(1);

        // Once v1 is retired the new version alone still refuses the duplicate, through the unique index.
        ColumnEncryptionNationalIdProtector retired = protectorWith("v2", Map.of("v2", keyV2));
        assertThatThrownBy(() -> insert(students, retired, "E-000003")).isInstanceOf(DuplicateNationalIdException.class);
    }

    @Test
    void aStudentCanKeepItsOwnDocumentWhileTwoVersionsAreConfigured() {
        JooqStudentRepository students = new JooqStudentRepository(dsl);
        ColumnEncryptionNationalIdProtector before = protectorWith("v1", Map.of("v1", keyV1));
        ColumnEncryptionNationalIdProtector rotating = protectorWith("v2", Map.of("v1", keyV1, "v2", keyV2));
        UUID id = insert(students, before, "E-000001");

        transactionRunner().execute(context(), () -> {
            students.replaceBlindIndex(institution, id, rotating.protect(institution, id, NationalId.of(DOCUMENT)).blindIndex());
            return null;
        });
        // The edit that re-sends the same document for the same student is not a duplicate of itself.
        transactionRunner().execute(context(), () -> {
            students.update(institution,
                    Student.create(id, "E-000001", "Ana", "Mena", LocalDate.of(2012, 5, 20), "F", NationalId.of(DOCUMENT), NOW),
                    new com.confia.students.application.NationalIdStorage.Store(
                            rotating.protect(institution, id, NationalId.of(DOCUMENT))));
            return null;
        });

        assertThat(storedIndex(id)).startsWith("v2:");
    }
}
