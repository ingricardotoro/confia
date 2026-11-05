// Deliberate violation of apps-use-ui-public-entry (AW04): an application importing a file of
// packages/ui past its public entry.
import { anAtom } from "../../../packages/ui/src/atoms/an-atom.ts";

export const leaked = anAtom;
