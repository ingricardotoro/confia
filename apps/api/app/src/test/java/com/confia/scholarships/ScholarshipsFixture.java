package com.confia.scholarships;

import com.confia.catalog.infrastructure.JooqPaymentConceptDirectory;
import com.confia.charges.ChargesLedgerFixture;
import com.confia.organization.infrastructure.JooqAcademicStructureDirectory;
import com.confia.scholarships.application.DecideAward;
import com.confia.scholarships.application.ManageScholarships;
import com.confia.scholarships.application.RequestAward;
import com.confia.scholarships.application.ViewAwards;
import com.confia.scholarships.infrastructure.JooqAwardDecisionRepository;
import com.confia.scholarships.infrastructure.JooqAwardRepository;
import com.confia.scholarships.infrastructure.JooqScholarshipRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.students.infrastructure.JooqStudentDirectory;
import com.confia.support.StaffRoleFixtures;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * What the integration tests of the scholarships share: the institution of {@link ChargesLedgerFixture} (an academic year 2027 from
 * January 1 to November 30, a payment concept, students) with the real use cases of the scholarships over the real database and
 * staff accounts of the roles that request and decide. The scholarships use the system clock because the database refuses a decision
 * stamped with another time than now. People are fictional.
 */
public abstract class ScholarshipsFixture extends ChargesLedgerFixture {

    protected static final Clock NOW = Clock.systemUTC();
    protected static final String REASON = "La familia ya no cumple con los requisitos";

    protected static AuthenticatedActor actorOf(UUID institution, String roleCode) throws SQLException {
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institution, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW.instant(), NOW.instant().plusSeconds(900));
        StaffRoleFixtures.committed(institution, actor.accountId(), roleCode);
        return actor;
    }

    protected static String request() {
        return UUID.randomUUID().toString();
    }

    protected ManageScholarships manageScholarships() {
        return new ManageScholarships(transactionRunner(), new JooqScholarshipRepository(dsl), new JooqAwardRepository(dsl),
                new JooqPaymentConceptDirectory(dsl), new JooqAuditLogWriter(dsl), NOW);
    }

    protected RequestAward requestAward() {
        return new RequestAward(transactionRunner(), new JooqScholarshipRepository(dsl), new JooqAwardRepository(dsl),
                new JooqAwardDecisionRepository(dsl), new JooqStudentDirectory(dsl), new JooqAcademicStructureDirectory(dsl),
                new JooqAuditLogWriter(dsl), NOW);
    }

    protected DecideAward decideAward() {
        return new DecideAward(transactionRunner(), new JooqScholarshipRepository(dsl), new JooqAwardRepository(dsl),
                new JooqAwardDecisionRepository(dsl), new JooqStudentDirectory(dsl), new JooqAuditLogWriter(dsl), NOW);
    }

    protected ViewAwards viewAwards() {
        return new ViewAwards(transactionRunner(), new JooqScholarshipRepository(dsl), new JooqAwardRepository(dsl),
                new JooqAwardDecisionRepository(dsl), new JooqStudentDirectory(dsl));
    }

    /** The academic year of the seeded institution. */
    protected UUID yearOf(UUID institution) throws SQLException {
        try (var c = StaffRoleFixtures.ownerTransaction(institution);
                var s = c.prepareStatement("select id from organization_academic_year where institution_id = ?")) {
            s.setObject(1, institution);
            try (var rs = s.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
