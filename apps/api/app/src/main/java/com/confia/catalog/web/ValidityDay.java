package com.confia.catalog.web;

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

/** A day of a validity: between 2000-01-01 and 2100-12-31, both included. {@code null} is left to {@code @NotNull}. */
@Documented
@Constraint(validatedBy = ValidityDay.Validator.class)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@interface ValidityDay {

    String message() default "day-out-of-range";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<ValidityDay, LocalDate> {

        private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
        private static final LocalDate LATEST = LocalDate.of(2100, 12, 31);

        @Override
        public boolean isValid(LocalDate day, ConstraintValidatorContext context) {
            return day == null || (!day.isBefore(EARLIEST) && !day.isAfter(LATEST));
        }
    }
}
