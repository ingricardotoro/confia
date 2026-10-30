import type { Linter } from "eslint";

export function contractImportRestrictions(options?: { portal?: boolean }): Linter.Config;

export function uiSourceRules(options?: { portal?: boolean }): Linter.Config[];

export function confiaEslint(options: {
  tsconfigRootDir: string;
  ignores?: string[];
  portal?: boolean;
  ui?: boolean;
}): Linter.Config[];
