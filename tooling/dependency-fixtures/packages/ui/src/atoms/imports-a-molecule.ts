// Deliberate violation of ui-atoms-know-only-tokens: an atom reaching up to a molecule.
import { aMolecule } from "../molecules/a-molecule.ts";

export const leaked = aMolecule;
