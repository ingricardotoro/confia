package com.confia.students.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

/**
 * A birth date that is not before 1990-01-01: nobody in the registry is older, and the bound catches
 * typing mistakes. The violation is reported to the client as the field and the reason {@code
 * birth-date-floor}. A null is not this constraint's business ({@code NotNull} owns it).
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BirthDateFloor.Validator.class)
public @interface BirthDateFloor {

    String message() default "the birth date is before 1990-01-01";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** The earliest accepted day, which is also the one of the domain rule. */
    LocalDate FLOOR = LocalDate.of(1990, 1, 1);

    final class Validator implements ConstraintValidator<BirthDateFloor, LocalDate> {

        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            return value == null || !value.isBefore(FLOOR);
        }
    }
}
