// Accepted case, not a violation: domain code may import atoms and molecules, and may import a type
// from the contract through its public entry point.
import type { Money } from "@confia/contracts/portal";
import { anAtom } from "../atoms/an-atom.ts";
import { aMolecule } from "../molecules/a-molecule.ts";

export type Accepted = Money;
export const accepted = [anAtom, aMolecule];
