package com.confia.students.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StudentTest {

    /** 2026-03-01 15:00 UTC is still 2026-03-01 09:00 in Tegucigalpa. */
    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");
    private static final Instant LATER = Instant.parse("2026-04-01T15:00:00Z");
    private static final LocalDate BIRTH = LocalDate.of(2012, 5, 20);

    private static Student student() {
        return Student.create(UUID.randomUUID(), "E-000001", "Ana", "Perez", BIRTH, "F", null, NOW);
    }

    private static Student studentWith(String first, String last, LocalDate birth, String sex) {
        return Student.create(UUID.randomUUID(), "E-1", first, last, birth, sex, null, NOW);
    }

    // --- Names ---

    @Test
    void namesAreStrippedOfBorderSpaces() {
        Student created = studentWith("  Ana Maria ", "\tPerez  ", BIRTH, "F");

        assertThat(created.firstName()).isEqualTo("Ana Maria");
        assertThat(created.lastName()).isEqualTo("Perez");
    }

    @Test
    void aNameOfOneAndOfOneHundredCharactersIsAccepted() {
        assertThat(studentWith("A", "B", BIRTH, "M").firstName()).isEqualTo("A");
        assertThat(studentWith("A".repeat(100), " " + "B".repeat(100) + " ", BIRTH, "M").lastName())
                .hasSize(100);
    }

    @Test
    void aBlankNullOrTooLongNameIsRefused() {
        assertThatThrownBy(() -> studentWith("   ", "Perez", BIRTH, "F")).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> studentWith(null, "Perez", BIRTH, "F")).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> studentWith("Ana", "", BIRTH, "F")).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> studentWith("A".repeat(101), "Perez", BIRTH, "F"))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> studentWith("Ana", "P".repeat(101), BIRTH, "F"))
                .isInstanceOf(InvalidStudentException.class);
    }

    // --- Birth date ---

    @Test
    void theFirstDayOf1990AndTodayAreAccepted() {
        assertThat(studentWith("Ana", "Perez", LocalDate.of(1990, 1, 1), "F").birthDate())
                .isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(studentWith("Ana", "Perez", LocalDate.of(2026, 3, 1), "F").birthDate())
                .isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void theDayBefore1990IsRefused() {
        assertThatThrownBy(() -> studentWith("Ana", "Perez", LocalDate.of(1989, 12, 31), "F"))
                .isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void tomorrowAndANullDateAreRefused() {
        assertThatThrownBy(() -> studentWith("Ana", "Perez", LocalDate.of(2026, 3, 2), "F"))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> studentWith("Ana", "Perez", null, "F"))
                .isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void todayIsJudgedByTheClockOfHondurasAndNotByUtc() {
        // 03:00 UTC of March 2nd is still March 1st, 21:00 in Tegucigalpa: March 2nd is the future.
        Instant lateEvening = Instant.parse("2026-03-02T03:00:00Z");

        assertThatThrownBy(() -> Student.create(UUID.randomUUID(), "E-1", "Ana", "Perez",
                LocalDate.of(2026, 3, 2), "F", null, lateEvening)).isInstanceOf(InvalidStudentException.class);
        assertThat(Student.create(UUID.randomUUID(), "E-1", "Ana", "Perez", LocalDate.of(2026, 3, 1), "F", null,
                lateEvening).birthDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    // --- Sex and status ---

    @ParameterizedTest
    @ValueSource(strings = {"F", "M", "UNSPECIFIED"})
    void theThreeSexValuesAreAccepted(String sex) {
        assertThat(studentWith("Ana", "Perez", BIRTH, sex).sex().name()).isEqualTo(sex);
    }

    @ParameterizedTest
    @ValueSource(strings = {"f", "X", "", "FEMALE"})
    void anyOtherSexIsRefused(String sex) {
        assertThatThrownBy(() -> studentWith("Ana", "Perez", BIRTH, sex)).isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void aNullSexIsRefused() {
        assertThatThrownBy(() -> studentWith("Ana", "Perez", BIRTH, null))
                .isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void aStudentIsBornActive() {
        assertThat(student().status()).isEqualTo(StudentStatus.ACTIVE);
    }

    @Test
    void anUnknownStatusIsRefusedOnEdit() {
        Student before = student();

        assertThatThrownBy(() -> before.edit("Ana", "Perez", BIRTH, "F", "DELETED", NationalIdChange.Keep.INSTANCE,
                LATER)).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> before.edit("Ana", "Perez", BIRTH, "F", null, NationalIdChange.Keep.INSTANCE,
                LATER)).isInstanceOf(InvalidStudentException.class);
    }

    // --- The code ---

    @Test
    void theCodeIsStrippedAndLimitedToThirtyCharacters() {
        assertThat(Student.create(UUID.randomUUID(), "  A-1 ", "Ana", "Perez", BIRTH, "F", null, NOW)
                .studentCode()).isEqualTo("A-1");
        assertThat(Student.create(UUID.randomUUID(), "C".repeat(30), "Ana", "Perez", BIRTH, "F", null, NOW)
                .studentCode()).hasSize(30);
        assertThatThrownBy(() -> Student.create(UUID.randomUUID(), "C".repeat(31), "Ana", "Perez", BIRTH, "F",
                null, NOW)).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> Student.create(UUID.randomUUID(), "  ", "Ana", "Perez", BIRTH, "F", null, NOW))
                .isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void generatedCodesAreSixDigitsPaddedWithZerosAndGrowPastThem() {
        assertThat(StudentCode.generated(1)).isEqualTo("E-000001");
        assertThat(StudentCode.generated(42)).isEqualTo("E-000042");
        assertThat(StudentCode.generated(999_999)).isEqualTo("E-999999");
        assertThat(StudentCode.generated(1_000_000)).isEqualTo("E-1000000");
        assertThatThrownBy(() -> StudentCode.generated(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEditNeverChangesTheCodeNorTheCreationInstant() {
        Student before = student();

        Student after = before.edit("Luis", "Lopez", LocalDate.of(2011, 1, 2), "M", "INACTIVE",
                NationalIdChange.Keep.INSTANCE, LATER);

        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.studentCode()).isEqualTo("E-000001");
        assertThat(after.createdAt()).isEqualTo(NOW);
        assertThat(after.updatedAt()).isEqualTo(LATER);
        assertThat(after.firstName()).isEqualTo("Luis");
        assertThat(after.status()).isEqualTo(StudentStatus.INACTIVE);
    }

    @Test
    void anEditAppliesTheSameRulesAsTheCreation() {
        Student before = student();

        assertThatThrownBy(() -> before.edit(" ", "Perez", BIRTH, "F", "ACTIVE", NationalIdChange.Keep.INSTANCE, LATER))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> before.edit("Ana", "Perez", LocalDate.of(1989, 12, 31), "F", "ACTIVE",
                NationalIdChange.Keep.INSTANCE, LATER)).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> before.edit("Ana", "Perez", LocalDate.of(2026, 5, 1), "F", "ACTIVE",
                NationalIdChange.Keep.INSTANCE, LATER)).isInstanceOf(InvalidStudentException.class);
    }

    // --- The document ---

    @Test
    void aStudentWithoutDocumentSaysSo() {
        Student created = student();

        assertThat(created.hasNationalId()).isFalse();
    }

    @Test
    void createdWithADocumentItKeepsOnlyThatThereIsOne() {
        Student created = Student.create(UUID.randomUUID(), "E-1", "Ana", "Perez", BIRTH, "F",
                NationalId.of("0801-2012-01234"), NOW);

        assertThat(created.hasNationalId()).isTrue();
        assertThat(created.toString()).doesNotContain("0801").doesNotContain("20120").doesNotContain("1234");
        assertThat(java.util.Arrays.stream(Student.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .noneMatch(name -> name.toLowerCase().contains("last4") || name.toLowerCase().contains("mask"));
    }

    @Test
    void keepingReplacingAndRemovingTheDocument() {
        Student withId = Student.create(UUID.randomUUID(), "E-1", "Ana", "Perez", BIRTH, "F",
                NationalId.of("08012012-01234"), NOW);

        Student kept = withId.edit("Ana", "Perez", BIRTH, "F", "ACTIVE", NationalIdChange.Keep.INSTANCE, LATER);
        Student replaced = withId.edit("Ana", "Perez", BIRTH, "F", "ACTIVE", NationalIdChange.of("AB-99998888"),
                LATER);
        Student removed = withId.edit("Ana", "Perez", BIRTH, "F", "ACTIVE", NationalIdChange.of(""), LATER);

        assertThat(kept.hasNationalId()).isTrue();
        assertThat(replaced.hasNationalId()).isTrue();
        assertThat(removed.hasNationalId()).isFalse();
        assertThat(withId.edit("Ana", "Perez", BIRTH, "F", "ACTIVE", NationalIdChange.Keep.INSTANCE, LATER)
                .edit("Ana", "Perez", BIRTH, "F", "ACTIVE", NationalIdChange.of(""), LATER)
                .edit("Ana", "Perez", BIRTH, "F", "ACTIVE", NationalIdChange.Keep.INSTANCE, LATER)
                .hasNationalId()).isFalse();
    }
}
