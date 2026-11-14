package com.confia.guardians.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What replacing the current links of a student with a validated set does, keeping the history: a link whose
 * guardian is absent from the set, or whose percentage, relationship or flags differ, is closed on {@code today}
 * and, if the guardian is in the set, a new link opens on {@code today}; a link that is identical is left exactly
 * as it is. A closed link never comes back and is never edited.
 *
 * @param closed the current links that stop applying today (still as they were: the repository sets their end)
 * @param opened the new links that start applying today
 * @param kept the current links that stay
 */
public record LinkReplacement(List<GuardianLink> closed, List<GuardianLink> opened, List<GuardianLink> kept) {

    private static final ZoneId HONDURAS = ZoneId.of("America/Tegucigalpa");

    public LinkReplacement {
        closed = List.copyOf(closed);
        opened = List.copyOf(opened);
        kept = List.copyOf(kept);
    }

    /**
     * The replacement of {@code current} (the current links of {@code studentId}) with {@code split}.
     *
     * @param ids supplies the identifier of each new link
     */
    public static LinkReplacement of(List<GuardianLink> current, ResponsibilitySplit split, UUID studentId,
            LocalDate today, UUID actor, Instant now, Supplier<UUID> ids) {
        Map<UUID, LinkDraft> wanted = new HashMap<>();
        split.links().forEach(draft -> wanted.put(draft.guardianId(), draft));
        List<GuardianLink> closed = new ArrayList<>();
        List<GuardianLink> kept = new ArrayList<>();
        List<GuardianLink> opened = new ArrayList<>();
        Map<UUID, Boolean> covered = new HashMap<>();
        for (GuardianLink link : current) {
            LinkDraft draft = wanted.get(link.guardianId());
            if (draft != null && link.sameAs(draft)) {
                kept.add(link);
                covered.put(link.guardianId(), true);
            } else {
                closed.add(link);
            }
        }
        for (LinkDraft draft : split.links()) {
            if (!covered.containsKey(draft.guardianId())) {
                opened.add(GuardianLink.open(ids.get(), studentId, draft, today, actor, now));
            }
        }
        return new LinkReplacement(closed, opened, kept);
    }

    /** The day it is in Honduras at {@code instant}: the "today" on which links close and open. */
    public static LocalDate dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, HONDURAS);
    }

    /** Whether the replacement changes anything at all. */
    public boolean changed() {
        return !closed.isEmpty() || !opened.isEmpty();
    }
}
