package com.confia.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@link CommittingPostgresIntegrationTest}'s catalog-derived truncation set
 * (design.md, decision 9, piece 3; task 2.4): confirms the set excludes {@code shared_audit_log}
 * and {@code shared_audit_chain_head} on its own, straight from {@link
 * CommittingPostgresIntegrationTest#tablesWithoutABeforeTruncateTrigger()} — the exact derivation
 * the base class's own {@code @AfterEach} uses — never from a hand-written list. Both tables carry a
 * {@code BEFORE TRUNCATE} trigger since PR B2a's own V2 migration (task 2.2), which is precisely the
 * property that derivation excludes on.
 */
class CommittingBaseContractIT extends CommittingPostgresIntegrationTest {

    @Test
    void theDerivedTruncationSetExcludesBothAppendOnlyAuditTables() throws SQLException {
        List<String> tables = tablesWithoutABeforeTruncateTrigger();

        assertThat(tables)
                .as("the catalog-derived truncation set must exclude both append-only audit "
                        + "tables on its own, because each carries a BEFORE TRUNCATE trigger since "
                        + "the V2 migration — no hand-written list involved")
                .doesNotContain("shared_audit_log", "shared_audit_chain_head");
        // Membership, not just absence (same discipline as MultiTenantSchemaIT task 3.5): confirms
        // this ran over real catalog rows rather than silently passing over an empty result.
        assertThat(tables).contains("organization_institution");
    }

    @Test
    void truncatingCommittedTablesNeverThrowsEvenThoughTheAuditTablesAreSkipped()
            throws SQLException {
        truncateCommittedBusinessTables();
    }
}
