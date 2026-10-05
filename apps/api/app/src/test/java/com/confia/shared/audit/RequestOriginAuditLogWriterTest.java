package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.RequestOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestOriginAuditLogWriter}, the decorator that gives an audit entry the origin of the
 * request being served (web-edge-foundations design.md, decision 13; specs/web-edge, "El origen de
 * la petición llega a la bitácora de auditoría"): it completes only what the caller left null, a
 * value the caller set always wins, and outside a request the entry passes untouched. The
 * database side, with concurrent requests, is {@code RequestOriginAuditIT}.
 */
class RequestOriginAuditLogWriterTest {

    private static final UUID INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final List<AuditEntry> written = new ArrayList<>();
    private final AuditLogWriter writer = new RequestOriginAuditLogWriter(written::add);

    private static AuditEntry entry(String sourceIp, String userAgent) {
        return new AuditEntry(INSTITUTION, UUID.randomUUID(), "staff", "label", sourceIp,
                userAgent, REQUEST, "trace", "invoice.void", "invoice", "i-1", "success",
                "{\"a\":1}", "{\"a\":2}", "reason", UUID.randomUUID());
    }

    private static RequestOrigin origin(String ip, String userAgent) {
        return new RequestOrigin(UUID.randomUUID(), ip == null ? null
                : ClientAddress.parseLiteral(ip), userAgent);
    }

    private void appendDuring(RequestOrigin origin, AuditEntry entry) {
        ScopedValue.where(RequestOrigin.CURRENT, origin).run(() -> writer.append(entry));
    }

    @Test
    void anEntryWrittenDuringARequestGetsItsAddressAndAgentAndNothingElseChanges() {
        AuditEntry entry = entry(null, null);

        appendDuring(origin("203.0.113.9", "agente-prueba"), entry);

        assertThat(written).hasSize(1);
        AuditEntry completed = written.get(0);
        assertThat(completed.sourceIp()).isEqualTo("203.0.113.9");
        assertThat(completed.userAgent()).isEqualTo("agente-prueba");
        assertThat(completed).usingRecursiveComparison()
                .ignoringFields("sourceIp", "userAgent").isEqualTo(entry);
    }

    @Test
    void anIpv6ClientIsWrittenAsItsCanonicalText() {
        appendDuring(origin("2001:DB8::1", "agente"), entry(null, null));

        assertThat(written.get(0).sourceIp()).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void aValueTheCallerSetAlwaysWinsAndOnlyTheNullOneIsCompleted() {
        appendDuring(origin("203.0.113.9", "agente-prueba"), entry("192.0.2.1", null));
        appendDuring(origin("203.0.113.9", "agente-prueba"), entry(null, "agente-explicito"));
        AuditEntry both = entry("192.0.2.1", "agente-explicito");
        appendDuring(origin("203.0.113.9", "agente-prueba"), both);

        assertThat(written).extracting(AuditEntry::sourceIp, AuditEntry::userAgent).containsExactly(
                tuple("192.0.2.1", "agente-prueba"),
                tuple("203.0.113.9", "agente-explicito"),
                tuple("192.0.2.1", "agente-explicito"));
        assertThat(written.get(2)).isSameAs(both);
    }

    @Test
    void anOriginWithoutAnAddressOrAnAgentLeavesThoseFieldsNull() {
        appendDuring(origin(null, "agente"), entry(null, null));
        appendDuring(origin("203.0.113.9", null), entry(null, null));

        assertThat(written).extracting(AuditEntry::sourceIp, AuditEntry::userAgent).containsExactly(
                tuple(null, "agente"),
                tuple("203.0.113.9", null));
    }

    @Test
    void anEntryWrittenFromAThreadWithNoBindingIsWrittenWithNullFieldsAndNothingInvented()
            throws Exception {
        AuditEntry entry = entry(null, null);

        // The origin is bound here, but a plain executor thread does not inherit a ScopedValue
        // (RequestOrigin Javadoc): the work handed to it sees no origin at all.
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            ScopedValue.where(RequestOrigin.CURRENT, origin("203.0.113.9", "agente-prueba"))
                    .call(() -> executor.submit(() -> writer.append(entry)).get(10,
                            TimeUnit.SECONDS));
        }

        assertThat(written).hasSize(1);
        assertThat(written.get(0)).isSameAs(entry);
        assertThat(written.get(0).sourceIp()).isNull();
        assertThat(written.get(0).userAgent()).isNull();
    }

    @Test
    void outsideARequestTheEntryPassesUntouchedAndNothingOfAPastRequestRemains() {
        AuditEntry first = entry(null, null);
        appendDuring(origin("203.0.113.9", "agente-prueba"), entry(null, null));
        AuditEntry afterwards = entry(null, null);

        writer.append(first);
        writer.append(afterwards);

        assertThat(written.get(1)).isSameAs(first);
        assertThat(written.get(2)).isSameAs(afterwards);
        assertThat(written.get(2).sourceIp()).isNull();
        assertThat(written.get(2).userAgent()).isNull();
    }
}
