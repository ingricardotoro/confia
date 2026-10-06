package com.confia.architecture.fixture.webedge.web;

import com.confia.architecture.fixture.layering.domain.BadDomain;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberate violation fixture (web-edge-foundations design.md, decision 20, rule W2a): a
 * controller whose public signature exposes a jOOQ {@code Record} as its return type and a {@code
 * domain} type, inside a generic argument, as a parameter. It is meta-annotated with
 * {@code @Controller} through {@code @RestController}, which is how production controllers will
 * be. Permanent, never removed, and never registered with a Spring context: no test context
 * may component-scan {@code com.confia.architecture.fixture}, or this controller would be
 * mapped as a real endpoint.
 */
@RestController
public class BadRecordReturningController {

    public org.jooq.Record recordStraightFromTheDatabase() {
        return null;
    }

    public void acceptDomainObjects(List<BadDomain> domainObjectsFromTheClient) {
        // Deliberately empty: only the signature matters to the rule.
    }
}
