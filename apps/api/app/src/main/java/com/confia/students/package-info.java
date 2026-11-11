/**
 * The {@code students} capability: the registry of the students of an institution
 * (docs/cortes/students-registry.md). One use case, {@code ManageStudents}, with its jOOQ adapter and its
 * four routes under {@code /api/v1/students}.
 *
 * <p>The national identity document is personal data of a minor: it is stored only encrypted by column,
 * duplicates are found through a blind index, and the API shows only whether there is one and its mask.
 * Nothing in this module is ever deleted: a student is made inactive with an update, and every write
 * leaves an entry in the audit trail that never carries the document.
 *
 * <p>Out of scope: enrollment and transfers, guardians and links, bulk import, consents, the financial
 * account of the student, and revealing the full document (it would need a permission and an access audit
 * of its own).
 */
package com.confia.students;
