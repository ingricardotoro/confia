package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.students.domain.DuplicateNationalIdException;
import com.confia.students.domain.DuplicateStudentCodeException;
import com.confia.students.domain.NationalId;
import com.confia.students.domain.ResourceNotFoundException;
import com.confia.students.domain.Student;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The port of the registry and the protector of the document over maps, for the unit tests of the use
 * case. The repository honors the contract the real adapter honors through the database: a taken code or
 * a taken blind index is a domain error, the counter is per institution, and every row is kept with its
 * institution, so a read of another institution finds nothing. The protector keeps the plaintext inside
 * its "ciphertext" on purpose, so a test can prove that no audit entry or view carries it.
 */
final class InMemoryStudents {

    record Key(UUID institution, UUID id) {
    }

    record Stored(Student student, ProtectedNationalId document) {
    }

    final List<String> events = new ArrayList<>();
    final Map<Key, Stored> rows = new LinkedHashMap<>();
    final Map<UUID, Long> counters = new HashMap<>();
    PageRequest lastRequest;
    StudentFilter lastFilter;

    final StudentRepository students = new Repository();
    final NationalIdProtector protector = new Protector();

    private static Key key(InstitutionId institution, UUID id) {
        return new Key(institution.value(), id);
    }

    List<Stored> rowsOf(InstitutionId institution) {
        return rows.entrySet().stream().filter(e -> e.getKey().institution().equals(institution.value()))
                .map(Map.Entry::getValue).toList();
    }

    /** Puts a student in without any rule, as a test arranges a state. */
    void arrange(InstitutionId institution, Student student, ProtectedNationalId document) {
        rows.put(key(institution, student.id()), new Stored(student, document));
    }

    static String blindIndexOf(InstitutionId institution, String normalized) {
        return "bi:" + institution.value() + ":" + normalized;
    }

    private final class Protector implements NationalIdProtector {

        @Override
        public ProtectedNationalId protect(InstitutionId institution, UUID studentId, NationalId nationalId) {
            events.add("protect");
            return new ProtectedNationalId("enc[" + nationalId.normalized() + "]",
                    blindIndexOf(institution, nationalId.normalized()));
        }
    }

    private final class Repository implements StudentRepository {

        @Override
        public Optional<Student> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(rows.get(key(institution, id))).map(Stored::student);
        }

        @Override
        public long nextCodeSequence(InstitutionId institution) {
            events.add("sequence");
            return counters.merge(institution.value(), 1L, Long::sum);
        }

        @Override
        public boolean codeExists(InstitutionId institution, String code) {
            return rowsOf(institution).stream().anyMatch(s -> s.student().studentCode().equals(code));
        }

        @Override
        public void insert(InstitutionId institution, Student student, ProtectedNationalId document) {
            guard(institution, student, document);
            rows.put(key(institution, student.id()), new Stored(student, document));
        }

        @Override
        public void update(InstitutionId institution, Student student, NationalIdStorage storage) {
            Stored current = rows.get(key(institution, student.id()));
            if (current == null) {
                throw new ResourceNotFoundException();
            }
            ProtectedNationalId document = switch (storage) {
                case NationalIdStorage.Keep keep -> current.document();
                case NationalIdStorage.Clear clear -> null;
                case NationalIdStorage.Store store -> store.document();
            };
            guard(institution, student, document);
            rows.put(key(institution, student.id()), new Stored(student, document));
        }

        private void guard(InstitutionId institution, Student student, ProtectedNationalId document) {
            for (Stored other : rowsOf(institution)) {
                if (other.student().id().equals(student.id())) {
                    continue;
                }
                if (other.student().studentCode().equals(student.studentCode())) {
                    throw new DuplicateStudentCodeException();
                }
                if (document != null && other.document() != null
                        && document.lookupIndexes().contains(other.document().blindIndex())) {
                    throw new DuplicateNationalIdException();
                }
            }
        }

        @Override
        public List<IndexedNationalId> lockNationalIdsNotIndexedWith(InstitutionId institution, String version,
                int limit) {
            events.add("lock-not-indexed-with:" + version);
            return rowsOf(institution).stream()
                    .filter(s -> s.document() != null && !s.document().blindIndex().startsWith(version + ":"))
                    .sorted(Comparator.comparing(s -> s.student().id()))
                    .limit(limit)
                    .map(s -> new IndexedNationalId(s.student().id(), s.document().encrypted()))
                    .toList();
        }

        @Override
        public void replaceBlindIndex(InstitutionId institution, UUID studentId, String blindIndex) {
            events.add("replace-blind-index");
            Stored current = rows.get(key(institution, studentId));
            if (current == null || current.document() == null) {
                throw new ResourceNotFoundException();
            }
            rows.put(key(institution, studentId), new Stored(current.student(),
                    new ProtectedNationalId(current.document().encrypted(), blindIndex)));
        }

        @Override
        public Page<Student> list(InstitutionId institution, PageRequest request, StudentFilter filter) {
            lastRequest = request;
            lastFilter = filter;
            Comparator<Student> order = switch (request.sortField()) {
                case "studentCode" -> Comparator.comparing(Student::studentCode);
                case "firstName" -> Comparator.comparing(Student::firstName);
                case "birthDate" -> Comparator.comparing(Student::birthDate);
                default -> Comparator.comparing(Student::lastName);
            };
            List<Student> matching = rowsOf(institution).stream().map(Stored::student)
                    .filter(s -> filter.status() == null || s.status() == filter.status())
                    .filter(s -> filter.text() == null || contains(s, filter.text())).toList();
            List<Student> sorted = matching.stream().sorted(request.ascending() ? order : order.reversed()).toList();
            int from = (int) Math.min(request.offset(), sorted.size());
            int to = Math.min(from + request.size(), sorted.size());
            return new Page<>(sorted.subList(from, to), sorted.size(), request.page(), request.size());
        }

        private static boolean contains(Student student, String text) {
            String needle = text.toLowerCase(Locale.ROOT);
            return student.firstName().toLowerCase(Locale.ROOT).contains(needle)
                    || student.lastName().toLowerCase(Locale.ROOT).contains(needle)
                    || student.studentCode().toLowerCase(Locale.ROOT).contains(needle);
        }
    }
}
