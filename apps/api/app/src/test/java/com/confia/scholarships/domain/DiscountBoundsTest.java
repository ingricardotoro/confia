package com.confia.scholarships.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The bounds a discount and a discount assignment keep on their own: the version, the calendar window of 2000-01-01 to 2100-12-31,
 * the order of the validity and the covered concepts (docs/cortes/scholarships-discounts.md).
 */
class DiscountBoundsTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final LocalDate FROM = LocalDate.parse("2027-02-01");
    private static final LocalDate TO = LocalDate.parse("2027-06-30");
    private static final LocalDate WINDOW_START = LocalDate.parse("2000-01-01");
    private static final LocalDate WINDOW_END = LocalDate.parse("2100-12-31");
    private static final UUID CONCEPT = UUID.randomUUID();

    private static Discount discount(long version, LocalDate from, LocalDate to, Set<UUID> concepts) {
        return new Discount(UUID.randomUUID(), "CONV-10", "Convenio 10 %", DiscountKind.AGREEMENT, Benefit.percentage("10"), from, to,
                concepts, true, true, version, UUID.randomUUID(), NOW, NOW);
    }

    private static DiscountAssignment assignment(LocalDate from, LocalDate to) {
        return new DiscountAssignment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), from, to, AwardStatus.PENDING,
                UUID.randomUUID(), NOW);
    }

    private static String field(Throwable e) {
        return ((InvalidScholarshipsValueException) e).field();
    }

    static Stream<Arguments> validitiesOutOfTheWindow() {
        return Stream.of(
                Arguments.of(null, TO, "validFrom"),
                Arguments.of(WINDOW_START.minusDays(1), TO, "validFrom"),
                Arguments.of(WINDOW_END.plusDays(1), WINDOW_END.plusDays(2), "validFrom"),
                Arguments.of(FROM, null, "validTo"),
                Arguments.of(FROM, FROM.minusDays(1), "validTo"),
                Arguments.of(FROM, WINDOW_END.plusDays(1), "validTo"));
    }

    // --- Discount ---

    @Test
    void aDiscountWithANegativeVersionIsRefused() {
        assertThatThrownBy(() -> discount(-1, FROM, TO, Set.of(CONCEPT))).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @ParameterizedTest
    @MethodSource("validitiesOutOfTheWindow")
    void aDiscountWithAValidityOutOfTheWindowOrOutOfOrderIsRefused(LocalDate from, LocalDate to, String field) {
        assertThatThrownBy(() -> discount(0, from, to, Set.of(CONCEPT))).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(DiscountBoundsTest::field).isEqualTo(field);
    }

    @Test
    void aDiscountOnTheEdgesOfTheWindowIsAccepted() {
        assertThatCode(() -> discount(0, WINDOW_START, WINDOW_END, Set.of(CONCEPT))).doesNotThrowAnyException();
    }

    @Test
    void aDiscountWithoutConceptsOrWithAMissingConceptIsRefused() {
        Set<UUID> withNull = new HashSet<>(Arrays.asList(CONCEPT, null));

        assertThatThrownBy(() -> discount(0, FROM, TO, null)).extracting(DiscountBoundsTest::field).isEqualTo("concepts");
        assertThatThrownBy(() -> discount(0, FROM, TO, Set.of())).extracting(DiscountBoundsTest::field).isEqualTo("concepts");
        assertThatThrownBy(() -> discount(0, FROM, TO, withNull)).extracting(DiscountBoundsTest::field).isEqualTo("concepts");
    }

    static Stream<Arguments> assignmentsOutOfTheDiscountValidity() {
        return Stream.of(
                Arguments.of(null, TO, "validFrom"),
                Arguments.of(FROM.minusDays(1), TO, "validFrom"),
                Arguments.of(TO.plusDays(1), TO.plusDays(2), "validFrom"),
                Arguments.of(FROM, null, "validTo"),
                Arguments.of(FROM, TO.plusDays(1), "validTo"),
                Arguments.of(TO, FROM.minusDays(1), "validTo"));
    }

    @ParameterizedTest
    @MethodSource("assignmentsOutOfTheDiscountValidity")
    void anAssignmentOutsideTheValidityOfItsDiscountIsRefused(LocalDate from, LocalDate to, String field) {
        Discount discount = discount(0, FROM, TO, Set.of(CONCEPT));

        assertThatThrownBy(() -> discount.requireAssignable(from, to)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(DiscountBoundsTest::field).isEqualTo(field);
    }

    @Test
    void anAssignmentOnTheEdgesOfTheDiscountValidityIsAccepted() {
        Discount discount = discount(0, FROM, TO, Set.of(CONCEPT));

        assertThatCode(() -> discount.requireAssignable(FROM, TO)).doesNotThrowAnyException();
        assertThatCode(() -> discount.requireAssignable(TO, TO)).doesNotThrowAnyException();
    }

    // --- Benefit as the request names it ---

    @Test
    void aBenefitIsReadFromTheRequestOnlyWithTheValueOfItsType() {
        assertThat(Benefit.of("PERCENTAGE", "10", null).sameAs(Benefit.percentage("10"))).isTrue();
        assertThat(Benefit.of("FIXED_AMOUNT", null, "150.00").sameAs(Benefit.fixed("150.00"))).isTrue();
    }

    static Stream<Arguments> benefitsThatDoNotMatchTheirType() {
        return Stream.of(
                Arguments.of("PERCENTAGE", null, null),
                Arguments.of("PERCENTAGE", "10", "150.00"),
                Arguments.of("PERCENTAGE", null, "150.00"),
                Arguments.of("FIXED_AMOUNT", null, null),
                Arguments.of("FIXED_AMOUNT", "10", "150.00"),
                Arguments.of("FIXED_AMOUNT", "10", null),
                Arguments.of("OTHER", "10", null),
                Arguments.of(null, "10", null));
    }

    @ParameterizedTest
    @MethodSource("benefitsThatDoNotMatchTheirType")
    void aBenefitWhoseValuesDoNotMatchItsTypeIsRefused(String type, String percent, String fixedAmount) {
        assertThatThrownBy(() -> Benefit.of(type, percent, fixedAmount)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(DiscountBoundsTest::field).isEqualTo("benefitType");
    }

    @Test
    void twoBenefitsAreTheSameOnlyWithTheSameTypeAndValue() {
        assertThat(Benefit.percentage("10").sameAs(Benefit.percentage("10.00"))).isTrue();
        assertThat(Benefit.percentage("10").sameAs(Benefit.percentage("15"))).isFalse();
        assertThat(Benefit.fixed("150.00").sameAs(Benefit.fixed("200.00"))).isFalse();
        assertThat(Benefit.percentage("10").sameAs(Benefit.fixed("10.00"))).isFalse();
    }

    // --- Discount assignment ---

    @ParameterizedTest
    @MethodSource("validitiesOutOfTheWindow")
    void anAssignmentWithAValidityOutOfTheWindowOrOutOfOrderIsRefused(LocalDate from, LocalDate to, String field) {
        assertThatThrownBy(() -> assignment(from, to)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(DiscountBoundsTest::field).isEqualTo(field);
    }

    @Test
    void anAssignmentOnTheEdgesOfTheWindowIsAccepted() {
        assertThatCode(() -> assignment(WINDOW_START, WINDOW_END)).doesNotThrowAnyException();
    }

    @Test
    void anAssignmentPrintsItsIdentityAndStatusOnly() {
        DiscountAssignment assignment = assignment(FROM, TO);

        assertThat(assignment.toString()).isEqualTo("DiscountAssignment[id=" + assignment.id() + ", discountId="
                + assignment.discountId() + ", status=PENDING]").doesNotContain(assignment.studentId().toString());
    }
}
