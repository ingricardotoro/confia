package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.application.ManagePostingAccounts.Entry;
import com.confia.charges.domain.InactiveReferenceException;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.PostingAccountInvalidException;
import com.confia.charges.domain.PostingAccountKey;
import com.confia.charges.domain.PostingAccountMap;
import com.confia.charges.domain.PostingPurpose;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.charges.domain.StaleVersionException;
import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The use case of the map of posting accounts on in-memory ports and the stub of the transactional component
 * (docs/cortes/ledger-chart-of-accounts.md): which accounts are accepted for each purpose, the partial and versioned upsert,
 * that what is refused leaves neither a row nor an entry, and the audit trail. The database rules are proven by the
 * integration tests.
 */
class ManagePostingAccountsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T15:00:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institutionUuid,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    private final String requestId = UUID.randomUUID().toString();
    private final Rows rows = new Rows();
    private final Directory directory = new Directory();
    private final ManagePostingAccounts service = new ManagePostingAccounts(
            JdbcStubTransactions.runner(transactionCalls), rows, directory, auditWriter,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID receivable = directory.add(institution, "ASSET", true, true);
    private final UUID revenue = directory.add(institution, "INCOME", false, true);
    private final UUID otherRevenue = directory.add(institution, "INCOME", false, true);
    private final UUID tax = directory.add(institution, "LIABILITY", false, true);

    private static Entry entry(String purpose, String category, UUID account, Long version) {
        return new Entry(purpose, category, account, version);
    }

    private List<PostingAccountView> loadCompleteMap() {
        return service.set(actor, requestId, List.of(entry("RECEIVABLE", null, receivable, null),
                entry("REVENUE", null, revenue, null), entry("TAX_PAYABLE", null, tax, null)));
    }

    @Test
    void aMapIsLoadedInOneRequestAndTheAccrualEngineReadsItBack() {
        List<PostingAccountView> map = loadCompleteMap();

        assertThat(map).extracting(PostingAccountView::purpose).containsExactly("RECEIVABLE", "REVENUE", "TAX_PAYABLE");
        assertThat(map).extracting(PostingAccountView::version).containsOnly(0L);
        PostingAccountMap engineView = new PostingAccountMap(rows.list(institution));
        assertThat(engineView.receivable()).contains(receivable);
        assertThat(engineView.taxPayable()).contains(tax);
        assertThat(engineView.revenueFor("TUITION")).contains(revenue);
        assertThat(audit).hasSize(3).allSatisfy(e -> {
            assertThat(e.action()).isEqualTo("charges.posting_account.updated");
            assertThat(e.entityType()).isEqualTo("charges.posting_account");
            assertThat(e.beforeValue()).isNull();
            assertThat(e.institutionId()).isEqualTo(institutionUuid);
        });
        assertThat(audit.get(0).afterValue())
                .isEqualTo("{\"purpose\":\"RECEIVABLE\",\"ledgerAccountId\":\"" + receivable + "\",\"version\":0}");
        assertThat(transactionCalls).contains("commit").doesNotContain("rollback");
    }

    @Test
    void aPartialRequestWritesOnlyWhatItNamesAndTheMapStaysReportablyIncomplete() {
        service.set(actor, requestId, List.of(entry("RECEIVABLE", null, receivable, null)));

        PostingAccountMap engineView = new PostingAccountMap(rows.list(institution));
        assertThat(engineView.receivable()).contains(receivable);
        assertThat(engineView.taxPayable()).isEmpty();
        assertThat(engineView.completeFor(List.of("TUITION"), false)).isFalse();
        assertThat(audit).hasSize(1);
    }

    @Test
    void aRevenueOfACategoryOverridesTheDefaultOnlyForThatCategory() {
        service.set(actor, requestId, List.of(entry("REVENUE", null, revenue, null),
                entry("REVENUE", "TRANSPORT", otherRevenue, null)));

        PostingAccountMap engineView = new PostingAccountMap(rows.list(institution));
        assertThat(engineView.revenueFor("TRANSPORT")).contains(otherRevenue);
        assertThat(engineView.revenueFor("TUITION")).contains(revenue);
    }

    @Test
    void replacingAnAccountNeedsTheVersionReadMovesItAndAuditsBeforeAndAfter() {
        loadCompleteMap();
        audit.clear();

        List<PostingAccountView> map = service.set(actor, requestId, List.of(entry("REVENUE", null, otherRevenue, 0L)));

        assertThat(map).filteredOn(v -> v.purpose().equals("REVENUE")).singleElement().satisfies(v -> {
            assertThat(v.ledgerAccountId()).isEqualTo(otherRevenue);
            assertThat(v.version()).isEqualTo(1);
        });
        assertThat(audit).singleElement().satisfies(e -> {
            assertThat(e.beforeValue()).contains(revenue.toString()).contains("\"version\":0");
            assertThat(e.afterValue()).contains(otherRevenue.toString()).contains("\"version\":1");
        });
    }

    @Test
    void sendingTheSameAccountAgainChangesNothingAndWritesNoEntry() {
        loadCompleteMap();
        audit.clear();

        List<PostingAccountView> map = service.set(actor, requestId, List.of(entry("RECEIVABLE", null, receivable, 0L)));

        assertThat(map).extracting(PostingAccountView::version).containsOnly(0L);
        assertThat(audit).isEmpty();
    }

    @Test
    void aStaleOrMissingVersionIsRefusedAndNothingOfTheRequestIsKept() {
        loadCompleteMap();
        audit.clear();

        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("REVENUE", null, otherRevenue, 5L))))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("REVENUE", null, otherRevenue, null))))
                .as("the row exists, so the editor must say which version it read")
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> service.set(actor, requestId,
                List.of(entry("REVENUE", "TUITION", otherRevenue, 0L))))
                .as("a version for a row that does not exist")
                .isInstanceOf(StaleVersionException.class);

        assertThat(rows.list(institution)).extracting(PostingAccountMap.Entry::ledgerAccountId)
                .containsExactlyInAnyOrder(receivable, revenue, tax);
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void anAccountOfAnotherInstitutionOrThatDoesNotExistIsNotFound() {
        UUID foreign = directory.add(new InstitutionId(UUID.randomUUID()), "ASSET", true, true);

        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("RECEIVABLE", null, foreign, null))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.set(actor, requestId,
                List.of(entry("RECEIVABLE", null, UUID.randomUUID(), null))))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(rows.list(institution)).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void anInactiveAccountIsRefusedAsAnInactiveReference() {
        UUID inactive = directory.add(institution, "ASSET", true, false);

        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("RECEIVABLE", null, inactive, null))))
                .isInstanceOf(InactiveReferenceException.class);
        assertThat(rows.list(institution)).isEmpty();
    }

    @Test
    void anAccountOfTheWrongTypeOrWithTheWrongStudentFlagIsRefusedForEachPurpose() {
        UUID plainAsset = directory.add(institution, "ASSET", false, true);
        UUID studentLiability = directory.add(institution, "LIABILITY", true, true);

        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("RECEIVABLE", null, revenue, null))))
                .as("an income account as receivable").isInstanceOf(PostingAccountInvalidException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("RECEIVABLE", null, plainAsset, null))))
                .as("an asset without student balances as receivable").isInstanceOf(PostingAccountInvalidException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("REVENUE", null, receivable, null))))
                .as("the receivable as revenue").isInstanceOf(PostingAccountInvalidException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("TAX_PAYABLE", null, revenue, null))))
                .as("an income account as tax payable").isInstanceOf(PostingAccountInvalidException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("TAX_PAYABLE", null, studentLiability, null))))
                .as("a liability that holds students as tax payable").isInstanceOf(PostingAccountInvalidException.class);

        assertThat(rows.list(institution)).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void oneBadEntryKeepsTheGoodOnesOfTheSameRequestOut() {
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("RECEIVABLE", null, receivable, null),
                entry("TAX_PAYABLE", null, revenue, null)))).isInstanceOf(PostingAccountInvalidException.class);

        assertThat(rows.list(institution)).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void malformedRequestsAreRefusedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.set(actor, requestId, List.of())).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, null)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("RECEIVABLE", null, null, null))))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("RECEIVABLE", "TUITION", receivable, null))))
                .as("a category on the receivable").isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("REVENUE", "BOOKS", revenue, null))))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.set(actor, requestId, List.of(entry("REVENUE", null, revenue, -1L))))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.set(actor, requestId,
                List.of(entry("REVENUE", null, revenue, null), entry("REVENUE", null, otherRevenue, null))))
                .as("the same key twice").isInstanceOf(InvalidChargesValueException.class);
        List<Entry> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(entry("RECEIVABLE", null, receivable, null));
        }
        assertThatThrownBy(() -> service.set(actor, requestId, tooMany)).isInstanceOf(InvalidChargesValueException.class);

        assertThat(rows.list(institution)).isEmpty();
        assertThat(directory.lockCalls).as("nothing was looked up").isZero();
    }

    @Test
    void theAccountsAreLockedInShareModeInOneCallBeforeAnyRowIsWritten() {
        loadCompleteMap();

        assertThat(directory.lockCalls).isEqualTo(1);
        assertThat(directory.lockedBeforeFirstWrite).isTrue();
    }

    @Test
    void theMapOfAnotherInstitutionIsNeverRead() {
        loadCompleteMap();
        AuthenticatedActor other = new AuthenticatedActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));

        assertThat(service.list(other, requestId)).isEmpty();
        assertThat(service.list(actor, requestId)).hasSize(3);
    }

    /** The map in memory. */
    private final class Rows implements PostingAccountRepository {

        final Map<UUID, Map.Entry<InstitutionId, PostingAccountRow>> stored = new HashMap<>();

        @Override
        public List<PostingAccountMap.Entry> list(InstitutionId institution) {
            return listRows(institution).stream().map(row -> new PostingAccountMap.Entry(row.key().purpose(),
                    row.key().conceptCategory(), row.ledgerAccountId())).toList();
        }

        @Override
        public List<PostingAccountRow> listRows(InstitutionId institution) {
            return stored.values().stream().filter(e -> e.getKey().equals(institution)).map(Map.Entry::getValue)
                    .sorted(Comparator.comparing((PostingAccountRow r) -> r.key().purpose())
                            .thenComparing(r -> r.key().conceptCategory() == null ? "" : r.key().conceptCategory()))
                    .toList();
        }

        @Override
        public Optional<PostingAccountRow> findRow(InstitutionId institution, PostingAccountKey key) {
            return listRows(institution).stream().filter(r -> r.key().equals(key)).findFirst();
        }

        @Override
        public PostingAccountRow insert(InstitutionId institution, UUID id, PostingAccountKey key, UUID account,
                Instant now) {
            directory.sawWrite();
            PostingAccountRow row = new PostingAccountRow(id, key, account, 0L, now);
            stored.put(id, Map.entry(institution, row));
            return row;
        }

        @Override
        public PostingAccountRow replaceAccount(InstitutionId institution, PostingAccountRow row, UUID account,
                long expectedVersion, Instant now) {
            directory.sawWrite();
            if (row.version() != expectedVersion) {
                throw new StaleVersionException();
            }
            PostingAccountRow after = new PostingAccountRow(row.id(), row.key(), account, expectedVersion + 1, now);
            stored.put(row.id(), Map.entry(institution, after));
            return after;
        }
    }

    /** The accounts of the chart in memory, scoped to an institution. */
    private static final class Directory implements LedgerAccountDirectory {

        private final Map<UUID, Map.Entry<InstitutionId, LedgerAccountRef>> accounts = new HashMap<>();
        int lockCalls;
        boolean lockedBeforeFirstWrite = true;
        private boolean written;

        UUID add(InstitutionId institution, String type, boolean holdsStudentBalances, boolean active) {
            UUID id = UUID.randomUUID();
            accounts.put(id, Map.entry(institution, new LedgerAccountRef(id, "X-" + accounts.size(), "Cuenta", type,
                    holdsStudentBalances, active)));
            return id;
        }

        void sawWrite() {
            if (lockCalls == 0) {
                lockedBeforeFirstWrite = false;
            }
            written = true;
        }

        @Override
        public Map<UUID, LedgerAccountRef> find(InstitutionId institutionId, Collection<UUID> accountIds) {
            Map<UUID, LedgerAccountRef> found = new HashMap<>();
            accountIds.forEach(id -> {
                Map.Entry<InstitutionId, LedgerAccountRef> entry = accounts.get(id);
                if (entry != null && entry.getKey().equals(institutionId)) {
                    found.put(id, entry.getValue());
                }
            });
            return found;
        }

        @Override
        public Map<UUID, LedgerAccountRef> lockShare(InstitutionId institutionId, Collection<UUID> accountIds) {
            if (written) {
                lockedBeforeFirstWrite = false;
            }
            lockCalls++;
            return find(institutionId, accountIds);
        }
    }
}
