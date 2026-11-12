/**
 * The {@code students} capability: the registry of the students of an institution
 * (docs/cortes/students-registry.md). One use case, {@code ManageStudents}, with its jOOQ adapter and its
 * four routes under {@code /api/v1/students}.
 *
 * <p>The national identity document is personal data of a minor: it is stored only encrypted by column,
 * duplicates are found through a blind index, and the API shows only whether there is one.
 * Nothing in this module is ever deleted: a student is made inactive with an update, and every write
 * leaves an entry in the audit trail that never carries the document.
 *
 * <p>It also holds the enrollments (docs/cortes/enrollments.md): the {@code ManageEnrollments} use case, its jOOQ
 * adapter and six routes under {@code /api/v1/enrollments} and {@code /api/v1/students/{studentId}/enrollments}.
 * A student is enrolled in a section of an academic year, moved to another section of the same year and ended as a
 * withdrawal, a transfer to another institution or a graduation. The sections and their years are read through the
 * port that {@code organization} publishes ({@code SectionDirectory}); this module never imports the domain of
 * {@code organization} nor reads its tables.
 *
 * <p>It also publishes, to {@code imports}, the write side of the bulk import of students
 * (docs/cortes/imports-students.md): the port {@code StudentImportGateway} (nested package {@code
 * application.importing}) and its implementation {@code StudentImport}, which judges the rows of a file with the rules of
 * this registry and registers the valid ones through {@code ManageStudents}. The reference each imported student had in
 * the source system is kept in {@code students_student.external_ref}.
 *
 * <p>Out of scope: guardians and links, consents, the financial
 * account of the student, and revealing the full document (it would need a permission and an access audit
 * of its own).
 */
package com.confia.students;
