package com.confia.students.infrastructure;

import com.confia.kernel.InstitutionId;
import com.confia.students.application.ReindexNationalIds;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Runs {@link ReindexNationalIds} once, at startup of the administrative process, for the institutions named
 * in {@code confia.students.reindex.institutions}: the documented command of a blind-index key rotation
 * (docs/03-seguridad.md section 7.3). It is registered only when that property is set, so a normal start does
 * nothing. It logs counts and the institution identifier, never a document, a ciphertext or an index.
 */
public final class ReindexNationalIdsRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReindexNationalIdsRunner.class);

    private final ReindexNationalIds reindex;
    private final List<UUID> institutions;

    public ReindexNationalIdsRunner(ReindexNationalIds reindex, List<UUID> institutions) {
        this.reindex = reindex;
        this.institutions = List.copyOf(institutions);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        UUID run = UUID.randomUUID();
        for (UUID institution : institutions) {
            long recomputed = reindex.reindex(new InstitutionId(institution), run);
            LOG.info("students_blind_index_reindex institution={} run={} recomputed={}", institution, run, recomputed);
        }
    }
}
