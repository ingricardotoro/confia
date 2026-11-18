package com.confia.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerAccountUsage;
import com.confia.ledger.domain.AccountInUseException;
import com.confia.ledger.domain.DuplicateAccountCodeException;
import com.confia.ledger.domain.InvalidLedgerAccountException;
import com.confia.ledger.domain.LedgerAccount;
import com.confia.ledger.domain.LedgerResourceNotFoundException;
import com.confia.ledger.domain.StaleAccountVersionException;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The use case of the chart of accounts on in-memory ports and the stub of the transactional component
 * (docs/cortes/ledger-chart-of-accounts.md): what is stored, what is audited, what a deactivation asks the other modules and
 * that what is refused leaves neither a row nor an entry. The database rules (the triggers, the grants, the isolation) are
 * proven by the integration tests. Codes in the tests are fictional.
 */
class ManageLedgerAccountsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T15:00:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institutionUuid,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    private final String requestId = UUID.randomUUID().toString();
    private final Accounts accounts = new Accounts();
    private final Set<UUID> referenced = new java.util.HashSet<>();
    private final LedgerAccountUsage usage = (inst, account) -> inst.equals(institution) && referenced.contains(account);
    private final ManageLedgerAccounts service = new ManageLedgerAccounts(
            JdbcStubTransactions.runner(transactionCalls), accounts, List.of(usage), auditWriter,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private LedgerAccountView receivable() {
        return service.create(actor, requestId, "X-100", "Cuentas por cobrar", "ASSET", true);
    }

    @Test
    void creatingAnAccountStoresItActiveAtVersionZeroAndWritesOneCreatedEntryInTheSameTransaction() {
        LedgerAccountView view = receivable();

        assertThat(view.code()).isEqualTo("X-100");
        assertThat(view.type()).isEqualTo("ASSET");
        assertThat(view.holdsStudentBalances()).isTrue();
        assertThat(view.active()).isTrue();
        assertThat(view.version()).isZero();
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(accounts.rows).containsKey(view.id());
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("ledger.account.created");
            assertThat(entry.entityType()).isEqualTo("ledger.account");
            assertThat(entry.entityId()).isEqualTo(view.id().toString());
            assertThat(entry.institutionId()).isEqualTo(institutionUuid);
            assertThat(entry.actorId()).isEqualTo(actor.accountId());
            assertThat(entry.beforeValue()).isNull();
            assertThat(entry.afterValue()).isEqualTo("{\"code\":\"X-100\",\"name\":\"Cuentas por cobrar\","
                    + "\"type\":\"ASSET\",\"holdsStudentBalances\":true,\"active\":true,\"version\":0}");
        });
        assertThat(transactionCalls).contains("commit").doesNotContain("rollback");
    }

    @Test
    void aSecondAccountWithTheSameCodeInOtherCapitalsIsADuplicateAndLeavesNoRowNorEntry() {
        receivable();
        audit.clear();

        assertThatThrownBy(() -> service.create(actor, requestId, "x-100", "Otra", "INCOME", false))
                .isInstanceOf(DuplicateAccountCodeException.class);

        assertThat(accounts.rows).hasSize(1);
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void aTypeOutsideTheSetAndAStudentFlagOnAnIncomeAccountAreRefusedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.create(actor, requestId, "X-1", "Cuenta", "REVENUE", false))
                .isInstanceOf(InvalidLedgerAccountException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, "X-1", "Cuenta", "INCOME", true))
                .isInstanceOf(InvalidLedgerAccountException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, "bad code", "Cuenta", "INCOME", false))
                .isInstanceOf(InvalidLedgerAccountException.class);

        assertThat(accounts.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void editingTheNameAndDeactivatingMovesTheVersionAndAuditsBeforeAndAfter() {
        LedgerAccountView created = service.create(actor, requestId, "X-200", "Ingresos", "INCOME", false);
        audit.clear();

        LedgerAccountView edited = service.update(actor, requestId, created.id(), 0, "Ingresos por colegiatura", false);

        assertThat(edited.name()).isEqualTo("Ingresos por colegiatura");
        assertThat(edited.active()).isFalse();
        assertThat(edited.version()).isEqualTo(1);
        assertThat(edited.code()).isEqualTo("X-200");
        assertThat(edited.type()).isEqualTo("INCOME");
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("ledger.account.updated");
            assertThat(entry.beforeValue()).contains("\"name\":\"Ingresos\"").contains("\"active\":true")
                    .contains("\"version\":0");
            assertThat(entry.afterValue()).contains("\"name\":\"Ingresos por colegiatura\"").contains("\"active\":false")
                    .contains("\"version\":1");
        });
    }

    @Test
    void anEditWithAStaleVersionChangesNothingAndWritesNoEntry() {
        LedgerAccountView created = receivable();
        service.update(actor, requestId, created.id(), 0, "Nuevo nombre", true);
        audit.clear();

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "Otro nombre", true))
                .isInstanceOf(StaleAccountVersionException.class);

        assertThat(accounts.rows.get(created.id()).name()).isEqualTo("Nuevo nombre");
        assertThat(accounts.rows.get(created.id()).version()).isEqualTo(1);
        assertThat(audit).isEmpty();
    }

    @Test
    void anAccountOfAnotherInstitutionOrThatDoesNotExistIsNotFound() {
        LedgerAccountView created = receivable();
        AuthenticatedActor other = new AuthenticatedActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));

        assertThatThrownBy(() -> service.update(other, requestId, created.id(), 0, "Robo", false))
                .isInstanceOf(LedgerResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(actor, requestId, UUID.randomUUID(), 0, "Nada", false))
                .isInstanceOf(LedgerResourceNotFoundException.class);

        assertThat(accounts.rows.get(created.id()).active()).isTrue();
    }

    @Test
    void anAccountThatAModuleStillPointsAtCannotBeDeactivatedButCanBeRenamedAndReactivated() {
        LedgerAccountView created = receivable();
        referenced.add(created.id());
        audit.clear();

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "Cuenta", false))
                .isInstanceOf(AccountInUseException.class);
        assertThat(accounts.rows.get(created.id()).active()).isTrue();
        assertThat(accounts.rows.get(created.id()).version()).isZero();
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");

        LedgerAccountView renamed = service.update(actor, requestId, created.id(), 0, "Por cobrar a estudiantes", true);
        assertThat(renamed.name()).isEqualTo("Por cobrar a estudiantes");
        assertThat(renamed.active()).isTrue();
    }

    @Test
    void anAccountNoModuleUsesCanBeDeactivatedAndAnInactiveOneCanBeReactivatedEvenIfAModuleAsks() {
        LedgerAccountView created = receivable();

        LedgerAccountView off = service.update(actor, requestId, created.id(), 0, "Cuenta", false);
        assertThat(off.active()).isFalse();

        referenced.add(created.id());
        LedgerAccountView on = service.update(actor, requestId, created.id(), off.version(), "Cuenta", true);
        assertThat(on.active()).isTrue();
        assertThat(on.version()).isEqualTo(2);
    }

    @Test
    void theListFiltersAndNeverLeaksAnotherInstitutionAndRefusesAFieldOutsideTheSet() {
        service.create(actor, requestId, "X-1", "Uno", "ASSET", true);
        LedgerAccountView income = service.create(actor, requestId, "X-2", "Dos", "INCOME", false);
        service.update(actor, requestId, income.id(), 0, "Dos", false);
        AuthenticatedActor other = new AuthenticatedActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));

        assertThat(service.list(actor, requestId, 0, 50, null, null, null).total()).isEqualTo(2);
        assertThat(service.list(actor, requestId, 0, 50, null, "INCOME", null).items()).extracting("code")
                .containsExactly("X-2");
        assertThat(service.list(actor, requestId, 0, 50, null, null, true).items()).extracting("code")
                .containsExactly("X-1");
        assertThat(service.list(actor, requestId, 0, 50, "code,desc", null, null).items()).extracting("code")
                .containsExactly("X-2", "X-1");
        assertThat(service.list(other, requestId, 0, 50, null, null, null).total()).isZero();
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, "holdsStudentBalances", null, null))
                .isInstanceOf(InvalidLedgerAccountException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 201, null, null, null))
                .isInstanceOf(InvalidLedgerAccountException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, null, "revenue", null))
                .isInstanceOf(InvalidLedgerAccountException.class);
    }

    /** The accounts in memory, by identifier; the institution of a row is remembered so isolation can be proven. */
    private static final class Accounts implements LedgerAccountRepository {

        final Map<UUID, LedgerAccount> rows = new HashMap<>();
        private final Map<UUID, InstitutionId> owners = new HashMap<>();

        @Override
        public Optional<LedgerAccount> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(a -> owners.get(id).equals(institution));
        }

        @Override
        public Optional<LedgerAccount> lockById(InstitutionId institution, UUID id) {
            return findById(institution, id);
        }

        @Override
        public void insert(InstitutionId institution, LedgerAccount account) {
            boolean duplicate = rows.values().stream().anyMatch(existing -> owners.get(existing.id()).equals(institution)
                    && existing.code().toLowerCase(Locale.ROOT).equals(account.code().toLowerCase(Locale.ROOT)));
            if (duplicate) {
                throw new DuplicateAccountCodeException();
            }
            rows.put(account.id(), account);
            owners.put(account.id(), institution);
        }

        @Override
        public void update(InstitutionId institution, LedgerAccount account, long expectedVersion) {
            LedgerAccount stored = findById(institution, account.id()).orElseThrow(LedgerResourceNotFoundException::new);
            if (stored.version() != expectedVersion) {
                throw new StaleAccountVersionException();
            }
            rows.put(account.id(), account);
        }

        @Override
        public Page<LedgerAccount> list(InstitutionId institution, AccountPageRequest request, AccountFilter filter) {
            java.util.Comparator<LedgerAccount> byField = java.util.Comparator.comparing(
                    (LedgerAccount a) -> request.sortField().equals("name") ? a.name() : a.code());
            List<LedgerAccount> matching = rows.values().stream()
                    .filter(a -> owners.get(a.id()).equals(institution))
                    .filter(a -> filter.type() == null || filter.type() == a.type())
                    .filter(a -> filter.active() == null || filter.active() == a.active())
                    .sorted(request.ascending() ? byField : byField.reversed()).toList();
            int from = (int) Math.min(request.offset(), matching.size());
            int to = Math.min(from + request.size(), matching.size());
            return new Page<>(matching.subList(from, to), matching.size(), request.page(), request.size());
        }
    }
}
