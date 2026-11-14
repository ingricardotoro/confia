package com.confia.students.application;

import com.confia.students.domain.StudentStatus;

/**
 * What narrows a list: a text to look for in the first name, the last name and the code (already
 * stripped, at least two characters, or null for no search) and a status (or null for both).
 */
public record StudentFilter(String text, StudentStatus status) {
}
