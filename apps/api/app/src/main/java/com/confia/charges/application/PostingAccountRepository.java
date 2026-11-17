package com.confia.charges.application;

import com.confia.charges.domain.PostingAccountMap;
import com.confia.kernel.InstitutionId;
import java.util.List;

/** Output port of the map of posting accounts of an institution: DATA, never code. */
public interface PostingAccountRepository {

    List<PostingAccountMap.Entry> list(InstitutionId institution);
}
