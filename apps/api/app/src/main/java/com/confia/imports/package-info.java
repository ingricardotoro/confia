/**
 * The {@code imports} capability: the migration tool that loads the data of the system an institution comes from
 * (docs/cortes/imports-students.md; deliverable 6 of phase F1). Its first cut is the students, from a CSV file: one
 * use case, {@code ManageImports}, with its jOOQ adapter and, since the second cut, the guardians and their links, seven routes under {@code /api/v1/imports}.
 *
 * <p>A file is <b>simulated</b> as many times as needed and then <b>applied</b>. Neither keeps the file, a row of it or
 * a rejected value: a batch stores counts and the SHA-256 of the file, and the rejections (line, column, reason) are
 * returned to the person who ran it and are not stored. The application only proceeds when the file has the
 * fingerprint of a simulation of the same account. Each valid row is registered by {@code students} through the same
 * use case as a hand registration (generated code, encrypted document with its blind index, audit), through the port
 * that module publishes; this module never imports the domain of {@code students} nor reads its tables. The reference
 * of each student in the source system makes the import idempotent: an applied file can be applied again and creates
 * nothing twice.
 *
 * <p>Out of scope: enrollments and the reconciliation report, and the {@code .xlsx}
 * format.
 */
package com.confia.imports;
