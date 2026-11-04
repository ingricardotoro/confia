package com.confia.architecture.fixture.authorization.methodsecurity;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** The method-level permission annotation of a design this change refused (D6). */
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    String value();
}
