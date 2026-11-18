package com.confia.charges.application;

/** A charge of a student with the code and the name of its concept, which the charge itself does not hold. */
public record StudentChargeView(ChargeRow charge, String conceptCode, String conceptName) {
}
