package com.confia.guardians.application;

import com.confia.guardians.domain.GuardianStatus;

/** What narrows a list of guardians: a text for the names and the code, and a status; both optional. */
public record GuardianFilter(String text, GuardianStatus status) {
}
