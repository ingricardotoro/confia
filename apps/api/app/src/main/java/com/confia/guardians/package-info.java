/**
 * The {@code guardians} capability: the registry of the payment guardians of an institution and their links to
 * the students (docs/cortes/guardians-registry.md). Two use cases, {@code ManageGuardians} and {@code
 * ManageGuardianLinks}, with their jOOQ adapters and six routes: four under {@code /api/v1/guardians} and two under
 * {@code /api/v1/students/{studentId}/guardians}.
 *
 * <p>The national identity document and the RTN of a guardian are stored only encrypted by column; duplicate
 * documents are found through a blind index and the API shows only whether there is one of each. Nothing in this
 * module is ever deleted: a guardian is made inactive with an update, and a link that changes is closed and replaced
 * by a new one, so the history of every financial responsibility split stays. Every write leaves an entry in the
 * audit trail that never carries a name, a document, an RTN, an e-mail, a phone or an address.
 *
 * <p>INV-32 (docs/02-modelo-de-dominio.md): the current percentages of the links of a student add up to exactly 100.
 * The links of a student are therefore replaced as an atomic set, validated in exact decimal arithmetic and
 * serialized per student by a lock on the student's row. The students are read through the port that {@code
 * students} publishes ({@code StudentDirectory}); this module never imports the domain of {@code students} nor reads
 * its tables.
 *
 * <p>Out of scope: the account of a guardian in the portal, e-mail verification and the {@code GuardianLinked}
 * event (F6) and consents and extra contact channels. The bulk import of guardians and links is offered to {@code imports} through the
 * ports of the nested package {@code application.importing} (docs/cortes/imports-guardians.md).
 */
package com.confia.guardians;
