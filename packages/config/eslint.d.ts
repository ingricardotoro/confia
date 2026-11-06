import type { Linter } from "eslint";

export function contractImportRestrictions(options?: { portal?: boolean }): Linter.Config;

export function uiSourceRules(options?: { portal?: boolean }): Linter.Config[];

export function appSourceRules(options?: { portal?: boolean }): Linter.Config[];

export function confiaEslint(options: {
  tsconfigRootDir: string;
  ignores?: string[];
  portal?: boolean;
  ui?: boolean;
  app?: boolean;
  react?: boolean;
}): Linter.Config[];
