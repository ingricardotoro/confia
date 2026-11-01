// Deliberate violation of ui-atoms-know-only-tokens: an atom reaching up to the domain layer.
import { aDomainComponent } from "../domain/a-domain-component.ts";

export const leaked = aDomainComponent;
