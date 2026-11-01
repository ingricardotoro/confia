// Deliberate violation of ui-molecules-never-import-domain: a molecule reaching up to the domain layer.
import { aDomainComponent } from "../domain/a-domain-component.ts";

export const leaked = aDomainComponent;
