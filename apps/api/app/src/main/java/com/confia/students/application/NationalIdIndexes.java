package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.students.domain.NationalId;
import java.util.List;

/**
 * Output port that computes the blind indexes of a document, under every configured key version and the current one
 * first, without encrypting or storing anything: what a read needs to find out whether a document is already held.
 */
public interface NationalIdIndexes {

    List<String> lookupIndexes(InstitutionId institutionId, NationalId nationalId);
}
