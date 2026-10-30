import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

// design.md decision 9: every component of the design system has a story with a Default export and a
// test next to it. A component is a directory under atoms/, molecules/ or domain/ that holds <Name>.tsx.

const LEVELS = ["atoms", "molecules", "domain"] as const;

export interface Component {
  /** `<level>/<directory>/<Name>`, relative to the scanned root. */
  readonly id: string;
  readonly name: string;
  readonly directory: string;
}

/** Components under `root`, in level order and then in directory order. */
export function listComponents(root: string): Component[] {
  const components: Component[] = [];
  for (const level of LEVELS) {
    const levelPath = join(root, level);
    if (!existsSync(levelPath)) continue;
    for (const entry of readdirSync(levelPath, { withFileTypes: true }).filter((item) => item.isDirectory())) {
      const directory = join(levelPath, entry.name);
      for (const file of readdirSync(directory)) {
        const match = /^([A-Z][A-Za-z0-9]*)\.tsx$/.exec(file);
        const name = match?.[1];
        if (name !== undefined && !/\.(stories|test)$/.test(name)) {
          components.push({ id: `${level}/${entry.name}/${name}`, name, directory });
        }
      }
    }
  }
  return components;
}

const DEFAULT_STORY_EXPORT = /export\s+const\s+Default\b|export\s*\{[^}]*\bDefault\b[^}]*\}/;

/** Components with no `<Name>.stories.tsx`, or whose story file exports no `Default` story. */
export function findComponentsWithoutStory(root: string): string[] {
  return listComponents(root)
    .filter(({ name, directory }) => {
      const story = join(directory, `${name}.stories.tsx`);
      return !existsSync(story) || !DEFAULT_STORY_EXPORT.test(readFileSync(story, "utf8"));
    })
    .map(({ id }) => id);
}

/** Components with no `<Name>.test.tsx` next to them. */
export function findComponentsWithoutTest(root: string): string[] {
  return listComponents(root)
    .filter(({ name, directory }) => !existsSync(join(directory, `${name}.test.tsx`)))
    .map(({ id }) => id);
}
