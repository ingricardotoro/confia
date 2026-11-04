package com.confia.architecture.fixture.domainpurity.alpha.domain;

import org.springframework.stereotype.Component;

/**
 * Deliberate violation fixture (build-integrity scenario BI132): a domain class that names a Spring
 * type, which the domain purity rule must reject. Permanent: it is what proves the rule rejects
 * something.
 */
@Component
public final class BadDomainUsesSpring {
}
