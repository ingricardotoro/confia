package com.confia.guardians.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The current links of a student with their exact total, computed by the server in exact decimal arithmetic: 100.00
 * when the student is assigned and 0.00 when it has no guardian yet.
 */
public record StudentGuardiansView(UUID studentId, String studentCode, BigDecimal totalPercent,
        List<StudentGuardianView> links) {

    public StudentGuardiansView {
        links = List.copyOf(links);
    }
}
