package com.confia.guardians.application;

import com.confia.guardians.domain.DuplicateExternalRefException;
import com.confia.guardians.domain.DuplicateNationalIdException;
import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianLink;
import com.confia.guardians.domain.NationalId;
import com.confia.guardians.domain.ResourceNotFoundException;
import com.confia.guardians.domain.Rtn;
import com.confia.kernel.InstitutionId;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The ports of the guardians, the directory of the students and the protector of the document over maps, for the
 * unit tests of the use cases. The repositories honor the contract the real adapters honor through the database: a
 * taken blind index is a domain error, the counter is per institution, every row is kept with its institution so a
 * read of another institution finds nothing, and a link is only inserted, read and closed. The protector keeps the
 * plaintext inside its "ciphertext" on purpose, so a test can prove that no audit entry or view carries it. Every
 * lock and write is recorded in {@link #events}, in order.
 */
final class InMemoryGuardians {

    record Key(UUID institution, UUID id) {
    }

    record Stored(Guardian guardian, ProtectedNationalId document, String rtn) {
    }

    final List<String> events = new ArrayList<>();
    final Map<Key, Stored> guardianRows = new LinkedHashMap<>();
    final Map<Key, StudentRef> studentRows = new LinkedHashMap<>();
    final Map<Key, GuardianLink> linkRows = new LinkedHashMap<>();
    final Map<Key, LocalDate> closedOn = new HashMap<>();
    final Map<UUID, Long> counters = new HashMap<>();
    /** The references of the source system: {@code institution|reference} to the identifier. */
    final Map<String, UUID> guardianRefs = new HashMap<>();
    final Map<String, UUID> studentRefs = new HashMap<>();

    static String refKey(InstitutionId institution, String reference) {
        return institution.value() + "|" + reference;
    }
    PageRequest lastRequest;
    GuardianFilter lastFilter;

    final GuardianRepository guardians = new GuardianStore();
    final GuardianLinkRepository links = new LinkStore();
    final StudentDirectory students = new Directory();
    final GuardianSecretsProtector protector = new Protector();

    static Key key(InstitutionId institution, UUID id) {
        return new Key(institution.value(), id);
    }

    static String blindIndexOf(InstitutionId institution, String normalized) {
        return "bi:" + institution.value() + ":" + normalized;
    }

    /** Puts a guardian in without any rule, as a test arranges a state. */
    void arrange(InstitutionId institution, Guardian guardian) {
        guardianRows.put(key(institution, guardian.id()), new Stored(guardian, null, null));
    }

    void arrange(InstitutionId institution, StudentRef student) {
        studentRows.put(key(institution, student.studentId()), student);
    }

    /** Puts a current link in without any rule. */
    void arrange(InstitutionId institution, GuardianLink link) {
        linkRows.put(key(institution, link.id()), link);
    }

    List<Stored> guardiansOf(InstitutionId institution) {
        return guardianRows.entrySet().stream().filter(e -> e.getKey().institution().equals(institution.value()))
                .map(Map.Entry::getValue).toList();
    }

    /** Every link of the institution, current and closed, as the repository would read them. */
    List<GuardianLink> allLinks(InstitutionId institution, UUID studentId) {
        return linkRows.entrySet().stream()
                .filter(e -> e.getKey().institution().equals(institution.value())
                        && e.getValue().studentId().equals(studentId))
                .map(e -> closed(e.getKey(), e.getValue())).toList();
    }

    private GuardianLink closed(Key key, GuardianLink link) {
        LocalDate end = closedOn.get(key);
        return end == null ? link : new GuardianLink(link.id(), link.guardianId(), link.studentId(),
                link.relationship(), link.percent(), link.primaryPayer(), link.billingRecipient(), link.validFrom(),
                end, link.createdBy(), link.createdAt());
    }

    private final class Protector implements GuardianSecretsProtector {

        @Override
        public ProtectedNationalId protect(InstitutionId institution, UUID guardianId, NationalId nationalId) {
            events.add("protect-document");
            return new ProtectedNationalId("enc[" + nationalId.normalized() + "]",
                    blindIndexOf(institution, nationalId.normalized()));
        }

        @Override
        public String protect(InstitutionId institution, UUID guardianId, Rtn rtn) {
            events.add("protect-rtn");
            return "enc[" + rtn.digits() + "]";
        }
    }

    private final class Directory implements StudentDirectory {

        @Override
        public Optional<StudentRef> find(InstitutionId institution, UUID studentId) {
            return Optional.ofNullable(studentRows.get(key(institution, studentId)));
        }

        @Override
        public Optional<StudentRef> lock(InstitutionId institution, UUID studentId) {
            events.add("lock-student");
            return find(institution, studentId);
        }

        @Override
        public Map<String, StudentRef> findByExternalRefs(InstitutionId institution, Collection<String> refs) {
            Map<String, StudentRef> found = new HashMap<>();
            refs.forEach(ref -> {
                UUID id = studentRefs.get(refKey(institution, ref));
                if (id != null) {
                    find(institution, id).ifPresent(student -> found.put(ref, student));
                }
            });
            return found;
        }

        @Override
        public Map<UUID, StudentRef> findAll(InstitutionId institution, Collection<UUID> studentIds) {
            Map<UUID, StudentRef> found = new HashMap<>();
            studentIds.forEach(id -> find(institution, id).ifPresent(ref -> found.put(id, ref)));
            return found;
        }
    }

    private final class GuardianStore implements GuardianRepository {

        @Override
        public Optional<Guardian> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(guardianRows.get(key(institution, id))).map(Stored::guardian);
        }

        @Override
        public Optional<Guardian> lockById(InstitutionId institution, UUID id) {
            events.add("lock-guardian");
            return findById(institution, id);
        }

        @Override
        public List<Guardian> findAll(InstitutionId institution, Collection<UUID> ids) {
            return ids.stream().map(id -> findById(institution, id)).flatMap(Optional::stream).toList();
        }

        @Override
        public List<Guardian> lockAllShared(InstitutionId institution, Collection<UUID> ids) {
            events.add("lock-guardians-shared");
            return findAll(institution, ids);
        }

        @Override
        public long nextCodeSequence(InstitutionId institution) {
            events.add("sequence");
            return counters.merge(institution.value(), 1L, Long::sum);
        }

        @Override
        public boolean codeExists(InstitutionId institution, String code) {
            return guardiansOf(institution).stream().anyMatch(stored -> stored.guardian().guardianCode().equals(code));
        }

        @Override
        public void insert(InstitutionId institution, Guardian guardian, ProtectedNationalId document,
                String rtnEncrypted) {
            refuseDuplicate(institution, guardian.id(), document);
            events.add("insert-guardian");
            guardianRows.put(key(institution, guardian.id()), new Stored(guardian, document, rtnEncrypted));
        }

        @Override
        public void insertImported(InstitutionId institution, Guardian guardian, ProtectedNationalId document,
                String rtnEncrypted, String externalRef) {
            if (guardianRefs.containsKey(refKey(institution, externalRef))) {
                throw new DuplicateExternalRefException();
            }
            insert(institution, guardian, document, rtnEncrypted);
            guardianRefs.put(refKey(institution, externalRef), guardian.id());
        }

        @Override
        public Set<String> existingExternalRefs(InstitutionId institution, Collection<String> refs) {
            return refs.stream().filter(ref -> guardianRefs.containsKey(refKey(institution, ref)))
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public Map<String, Guardian> findByExternalRefs(InstitutionId institution, Collection<String> refs) {
            Map<String, Guardian> found = new HashMap<>();
            refs.forEach(ref -> {
                UUID id = guardianRefs.get(refKey(institution, ref));
                if (id != null) {
                    findById(institution, id).ifPresent(guardian -> found.put(ref, guardian));
                }
            });
            return found;
        }

        @Override
        public void update(InstitutionId institution, Guardian guardian, Storage<ProtectedNationalId> document,
                Storage<String> rtn) {
            Stored before = guardianRows.get(key(institution, guardian.id()));
            if (before == null) {
                throw new ResourceNotFoundException();
            }
            ProtectedNationalId newDocument = switch (document) {
                case Storage.Keep<ProtectedNationalId> keep -> before.document();
                case Storage.Clear<ProtectedNationalId> clear -> null;
                case Storage.Store<ProtectedNationalId> store -> store.value();
            };
            String newRtn = switch (rtn) {
                case Storage.Keep<String> keep -> before.rtn();
                case Storage.Clear<String> clear -> null;
                case Storage.Store<String> store -> store.value();
            };
            refuseDuplicate(institution, guardian.id(), newDocument);
            events.add("update-guardian");
            guardianRows.put(key(institution, guardian.id()), new Stored(guardian, newDocument, newRtn));
        }

        private void refuseDuplicate(InstitutionId institution, UUID self, ProtectedNationalId document) {
            if (document == null) {
                return;
            }
            boolean taken = guardiansOf(institution).stream().anyMatch(stored -> stored.document() != null
                    && !stored.guardian().id().equals(self)
                    && stored.document().blindIndex().equals(document.blindIndex()));
            if (taken) {
                throw new DuplicateNationalIdException();
            }
        }

        @Override
        public Page<Guardian> list(InstitutionId institution, PageRequest request, GuardianFilter filter) {
            lastRequest = request;
            lastFilter = filter;
            Stream<Guardian> all = guardiansOf(institution).stream().map(Stored::guardian);
            if (filter.status() != null) {
                all = all.filter(guardian -> guardian.status() == filter.status());
            }
            if (filter.text() != null) {
                String text = filter.text().toLowerCase(Locale.ROOT);
                all = all.filter(guardian -> guardian.firstName().toLowerCase(Locale.ROOT).contains(text)
                        || guardian.lastName().toLowerCase(Locale.ROOT).contains(text)
                        || guardian.guardianCode().toLowerCase(Locale.ROOT).contains(text));
            }
            List<Guardian> sorted = all.sorted(Comparator.comparing(Guardian::lastName)
                    .thenComparing(Guardian::guardianCode)).toList();
            int from = (int) Math.min(request.offset(), sorted.size());
            int to = Math.min(from + request.size(), sorted.size());
            return new Page<>(sorted.subList(from, to), sorted.size(), request.page(), request.size());
        }
    }

    private final class LinkStore implements GuardianLinkRepository {

        @Override
        public List<GuardianLink> currentOfStudent(InstitutionId institution, UUID studentId) {
            events.add("read-links");
            return allLinks(institution, studentId).stream().filter(GuardianLink::current).toList();
        }

        @Override
        public List<GuardianLink> currentOfGuardian(InstitutionId institution, UUID guardianId) {
            return linkRows.entrySet().stream()
                    .filter(e -> e.getKey().institution().equals(institution.value())
                            && e.getValue().guardianId().equals(guardianId))
                    .map(e -> closed(e.getKey(), e.getValue())).filter(GuardianLink::current).toList();
        }

        @Override
        public Set<UUID> studentIdsWithCurrentLinks(InstitutionId institution, Collection<UUID> studentIds) {
            return studentIds.stream().filter(id -> !currentOfStudent(institution, id).isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public List<GuardianLink> historyOfStudent(InstitutionId institution, UUID studentId) {
            return allLinks(institution, studentId);
        }

        @Override
        public void insertAll(InstitutionId institution, Collection<GuardianLink> links) {
            for (GuardianLink link : links) {
                events.add("insert-link");
                linkRows.put(key(institution, link.id()), link);
            }
        }

        @Override
        public void close(InstitutionId institution, Collection<UUID> linkIds, LocalDate on) {
            for (UUID id : linkIds) {
                Key key = key(institution, id);
                if (!linkRows.containsKey(key) || closedOn.containsKey(key)) {
                    throw new IllegalStateException("a guardian link to close was not current");
                }
                events.add("close-link");
                closedOn.put(key, on);
            }
        }
    }
}
