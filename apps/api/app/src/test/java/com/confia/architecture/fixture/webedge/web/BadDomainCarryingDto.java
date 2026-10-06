package com.confia.architecture.fixture.webedge.web;

import com.confia.architecture.fixture.layering.domain.BadDomain;

/**
 * Deliberate violation fixture (web-edge-foundations design.md, decision 20, rule W2b): a DTO of a
 * {@code web} package whose components are a {@code domain} type and a jOOQ {@code Record}, the two
 * things a response must be rebuilt without. Permanent, never removed.
 */
public record BadDomainCarryingDto(BadDomain domainObjectLeakedIntoTheResponse,
        org.jooq.Record rowLeakedIntoTheResponse) {
}
