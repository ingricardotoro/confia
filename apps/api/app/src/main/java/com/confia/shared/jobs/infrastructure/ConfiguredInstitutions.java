package com.confia.shared.jobs.infrastructure;

import com.confia.kernel.InstitutionId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The institutions the worker acts on, read from the property {@value #PROPERTY} (environment
 * variable {@code CONFIA_JOBS_INSTITUTIONS}): a comma-separated list of UUIDs
 * (background-jobs-with-db-scheduler design.md decision 10, owner decision P5). The list is
 * mandatory, non-empty and without repetition, and a bad one stops the process at startup, so a
 * recurring task can never run against "every institution it finds" or against none by accident. It
 * is not a secret.
 *
 * <p>A message about a bad list names the property and the position of the offending entry and
 * never repeats the list or the entry. Discovering the institutions from the database is the debt of
 * phase 2 (foundations-plan, exploration).
 *
 * @param institutions the institutions in the order they were configured, never empty
 */
public record ConfiguredInstitutions(List<InstitutionId> institutions) {

    /** The property the list is read from. */
    public static final String PROPERTY = "confia.jobs.institutions";

    public ConfiguredInstitutions {
        institutions = List.copyOf(institutions);
        if (institutions.isEmpty()) {
            throw new IllegalStateException("the property " + PROPERTY
                    + " must list at least one institution");
        }
    }

    /**
     * Parses the raw value of the property.
     *
     * @param raw the comma-separated list, possibly {@code null}
     * @throws IllegalStateException when the list is absent, empty, has an entry that is not a UUID
     *     or repeats an entry
     */
    public static ConfiguredInstitutions parse(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().chars().allMatch(c -> c == ',' || c == ' ')) {
            throw new IllegalStateException("the property " + PROPERTY
                    + " is required and must list at least one institution");
        }
        String[] entries = raw.split(",", -1);
        List<InstitutionId> parsed = new ArrayList<>();
        Map<UUID, Integer> firstSeen = new HashMap<>();
        for (int position = 1; position <= entries.length; position++) {
            UUID id = uuidOf(entries[position - 1].strip(), position);
            Integer earlier = firstSeen.putIfAbsent(id, position);
            if (earlier != null) {
                throw new IllegalStateException("entry " + position + " of the property " + PROPERTY
                        + " repeats entry " + earlier);
            }
            parsed.add(new InstitutionId(id));
        }
        return new ConfiguredInstitutions(parsed);
    }

    private static UUID uuidOf(String entry, int position) {
        try {
            return UUID.fromString(entry);
        } catch (IllegalArgumentException e) {
            // The cause is not chained: its message would carry the entry.
            throw new IllegalStateException("entry " + position + " of the property " + PROPERTY
                    + " is not a valid UUID");
        }
    }
}
