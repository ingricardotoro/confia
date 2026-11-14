package com.confia.students.application;

import com.confia.students.domain.InvalidStudentException;
import java.util.Set;

/**
 * What a list asks for: a page counted from zero, a size of at most {@link #MAX_SIZE}, and one field
 * of a closed set to sort by. The server applies the limit whatever the client sent, and a field
 * outside the set is refused instead of reaching a query.
 *
 * @param page the page, from 0
 * @param size the rows per page, from 1 to {@link #MAX_SIZE}
 * @param sortField a member of the allowed set of the list
 * @param ascending the direction of the sort
 */
public record PageRequest(int page, int size, String sortField, boolean ascending) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public PageRequest {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new InvalidStudentException();
        }
    }

    /**
     * Reads the raw parameters of a list. {@code sort} is a field or a field followed by a comma and asc
     * or desc; a missing one means the default field and direction.
     */
    public static PageRequest of(int page, int size, String sort, Set<String> allowedFields,
            String defaultField, boolean defaultAscending) {
        if (sort == null || sort.isBlank()) {
            return new PageRequest(page, size, defaultField, defaultAscending);
        }
        String[] parts = sort.split(",", -1);
        String field = parts[0].strip();
        boolean ascending = true;
        if (parts.length > 2 || !allowedFields.contains(field)) {
            throw new InvalidStudentException();
        }
        if (parts.length == 2) {
            String direction = parts[1].strip();
            if (direction.equalsIgnoreCase("desc")) {
                ascending = false;
            } else if (!direction.equalsIgnoreCase("asc")) {
                throw new InvalidStudentException();
            }
        }
        return new PageRequest(page, size, field, ascending);
    }

    /** The offset of the first row, computed in long so a huge page cannot overflow. */
    public long offset() {
        return (long) page * size;
    }
}
