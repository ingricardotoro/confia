// Shared ESLint flat configuration (frontend-monorepo-and-contracts-pipeline, design.md decision 7).
// typescript-eslint in its strict, type-checked mode: each package passes its own directory so the
// project service finds that package's tsconfig.json.
import js from "@eslint/js";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

/**
 * The `no-restricted-imports` patterns of the contract mirror. Exported through
 * `contractImportRestrictions` for every package, and composed into the UI rule entries below
 * because in flat configuration a second `no-restricted-imports` entry replaces the options of the
 * first instead of merging with them (design-system-foundations-and-a11y, design.md section 2).
 *
 * @param {boolean} portal
 */
function contractPatterns(portal) {
  const patterns = [
    {
      group: ["@confia/contracts/src/*", "**/contracts/src/generated/**"],
      message:
        "Import @confia/contracts/admin or @confia/contracts/portal, never a generated file or an internal path.",
    },
  ];
  if (portal) {
    patterns.push({
      group: ["@confia/contracts/admin"],
      message: "The portal never compiles against the administrative contract (ADR-0003).",
    });
  }
  return patterns;
}

/**
 * Mirrors two of the dependency-cruiser rules in .dependency-cruiser.cjs so the editor flags them
 * before CI does (design.md decision 8): packages/contracts is reached only through its admin and
 * portal entry points, and the portal never imports the administrative contract.
 *
 * @param {{ portal?: boolean }} [options]
 */
export function contractImportRestrictions({ portal = false } = {}) {
  return { rules: { "no-restricted-imports": ["error", { patterns: contractPatterns(portal) }] } };
}

// ---------------------------------------------------------------------------------------------
// packages/ui rules (design-system-foundations-and-a11y, design.md decision 7). Core ESLint rules
// only: no third-party plugin has to support ESLint 10 for them to run.
// ---------------------------------------------------------------------------------------------

// ADR-0011: no interface text outside the i18next catalog. The two messages differ so a failing
// test can tell visible text from accessible text.
const ACCESSIBLE_ATTRIBUTES = String.raw`/^(?:aria-label|aria-description|title|alt|placeholder|label)$/`;
const JSX_TEXT = {
  selector: String.raw`JSXText[value=/\S/], :matches(JSXElement, JSXFragment) > JSXExpressionContainer > Literal[value=/\S/]`,
  message: "Hard-coded interface text. Resolve it from the es-HN catalog with useUiText().t (ADR-0011).",
};
const JSX_ACCESSIBLE_TEXT = {
  selector: [
    `JSXAttribute[name.name=${ACCESSIBLE_ATTRIBUTES}] > Literal`,
    `JSXAttribute[name.name=${ACCESSIBLE_ATTRIBUTES}] > JSXExpressionContainer > Literal`,
    `JSXAttribute[name.name=${ACCESSIBLE_ATTRIBUTES}] > JSXExpressionContainer > TemplateLiteral[expressions.length=0]`,
  ].join(", "),
  message: "Hard-coded accessible text. Resolve it from the es-HN catalog with useUiText().t (ADR-0011).",
};

// ADR-0011: numbers are formatted only by src/format, through Intl.
const MANUAL_FORMAT = {
  selector: "CallExpression[callee.property.name=/^(?:toFixed|toLocaleString)$/]",
  message:
    "Manual number formatting. Format through src/format (Intl.NumberFormat), never toFixed or toLocaleString (ADR-0011).",
};

// CLAUDE.md rules 1 and 3: an amount is a string from the API to Intl. No conversion, no rounding,
// no arithmetic on it. The formatting methods are listed here too, so a file in a money scope
// reports one finding per violation instead of two.
const MONEY_OPERATOR = String.raw`/^(?:[-+*%]|\*\*)=?$/`;
const MONEY_ARITHMETIC = {
  selector: [
    "CallExpression[callee.name=/^(?:Number|parseFloat|parseInt)$/]",
    "NewExpression[callee.name='Number']",
    "CallExpression[callee.property.name=/^(?:parseFloat|parseInt|toFixed|toPrecision|toLocaleString)$/]",
    "MemberExpression[object.name='Math']",
    "UnaryExpression[operator='+']",
    "UnaryExpression[operator='-'][argument.type!='Literal']",
    "UpdateExpression",
    `:matches(BinaryExpression, AssignmentExpression)[operator=${MONEY_OPERATOR}]`,
    // A slash cannot sit inside an esquery regular expression, so division is matched by value.
    ":matches(BinaryExpression, AssignmentExpression)[operator='/']",
    ":matches(BinaryExpression, AssignmentExpression)[operator='/=']",
  ].join(", "),
  message:
    "Money arithmetic or conversion. An amount stays a string; the server computes and rounds it (CLAUDE.md rules 1 and 3).",
};

// design.md decision 6: components read semantic tokens only. Primitives live in src/tokens.
const PRIMITIVE_TOKEN = {
  selector: "Literal[value=/--palette-/], TemplateElement[value.raw=/--palette-/]",
  message:
    "Primitive token outside src/tokens. Components use the semantic and component tokens (design.md decision 6).",
};

// Visual literals: a colour typed by hand instead of a token. The Tailwind default palette is
// matched by name because its classes (bg-blue-600) compile even though CONFIA defines none.
const DEFAULT_PALETTE =
  "slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose";
const COLOUR_UTILITIES =
  "bg|text|border|ring|ring-offset|fill|stroke|from|via|to|outline|decoration|divide|accent|caret|shadow|placeholder";
const VISUAL_LITERAL_SOURCE = [
  String.raw`#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b`,
  String.raw`\b(?:rgba?|hsla?|hwb|oklch|oklab|lab|lch)\(`,
  String.raw`--color-(?:${DEFAULT_PALETTE})-\d`,
  String.raw`(?:^|[\s:!])-?(?:${COLOUR_UTILITIES})-(?:${DEFAULT_PALETTE})-\d{2,3}(?![\w-])`,
].join("|");
const VISUAL_LITERAL = {
  selector: `Literal[value=/${VISUAL_LITERAL_SOURCE}/], TemplateElement[value.raw=/${VISUAL_LITERAL_SOURCE}/]`,
  message:
    "Visual literal. A colour is never typed by hand; use a semantic token or a utility that reads one (design.md decision 6).",
};

const FORMAT_SCOPE = "src/format/**";
const TESTS = ["**/*.test.{ts,tsx}", "**/*.test-d.ts"];
const STORIES = ["**/*.stories.tsx"];
const FIXTURES = ["**/__fixtures__/**"];

/**
 * One entry per file group, each carrying the COMPLETE selector list for that group: a later
 * `no-restricted-syntax` entry replaces the earlier one, it does not extend it, so the groups go
 * from general to specific and repeat what they keep.
 */
const SYNTAX_SCOPES = [
  { files: ["src/**/*.{ts,tsx}"], controls: [MANUAL_FORMAT, PRIMITIVE_TOKEN, VISUAL_LITERAL] },
  {
    files: ["src/**/*.tsx"],
    ignores: [...TESTS, ...STORIES, ...FIXTURES],
    controls: [JSX_TEXT, JSX_ACCESSIBLE_TEXT, MANUAL_FORMAT, PRIMITIVE_TOKEN, VISUAL_LITERAL],
  },
  // Primitives and hand-typed colours live here by design; the formatting ban still applies to the
  // code, but not to the guard tests, which format contrast ratios in their diagnostics.
  { files: ["src/tokens/**"], controls: [MANUAL_FORMAT] },
  { files: ["src/tokens/**/*.test.ts"], controls: [] },
  // src/format owns Intl. Its tests may format; its code may not compute (MONEY_ARITHMETIC).
  { files: [FORMAT_SCOPE], controls: [PRIMITIVE_TOKEN, VISUAL_LITERAL] },
  {
    files: [FORMAT_SCOPE],
    ignores: [...TESTS, ...STORIES, ...FIXTURES],
    controls: [MONEY_ARITHMETIC, PRIMITIVE_TOKEN, VISUAL_LITERAL],
  },
  {
    files: [`${FORMAT_SCOPE}/*.tsx`],
    ignores: [...TESTS, ...STORIES, ...FIXTURES],
    controls: [JSX_TEXT, JSX_ACCESSIBLE_TEXT, MONEY_ARITHMETIC, PRIMITIVE_TOKEN, VISUAL_LITERAL],
  },
  {
    files: ["src/domain/money-amount/**/*.{ts,tsx}"],
    ignores: [...TESTS, ...STORIES, ...FIXTURES],
    controls: [MONEY_ARITHMETIC, PRIMITIVE_TOKEN, VISUAL_LITERAL],
  },
  {
    files: ["src/domain/money-amount/**/*.tsx"],
    ignores: [...TESTS, ...STORIES, ...FIXTURES],
    controls: [JSX_TEXT, JSX_ACCESSIBLE_TEXT, MONEY_ARITHMETIC, PRIMITIVE_TOKEN, VISUAL_LITERAL],
  },
  // Fixtures are data for the guards: deliberate violations live there.
  { files: FIXTURES, controls: [] },
];

// design.md decision 10: the mirror of the dependency-cruiser layer rules. Data libraries are
// rejected everywhere in src (ui-never-fetches-data).
const DATA_LIBRARIES = ["@tanstack/react-query", "@tanstack/query-core", "axios", "ky", "swr"];
const NO_DATA_ACCESS = DATA_LIBRARIES.map((name) => ({
  name,
  message: "packages/ui is pure presentation: it never requests or caches server data (ADR-0006).",
}));
const NO_DOMAIN = {
  group: ["**/domain", "**/domain/**"],
  message: "Atoms and molecules never import the domain layer (ui-molecules-never-import-domain).",
};
const NO_MOLECULES = {
  group: ["**/molecules", "**/molecules/**"],
  message: "Atoms know only tokens and other atoms (ui-atoms-know-only-tokens).",
};

/** The single `no-restricted-imports` entry of a group: contract mirror + data access + its layers. */
function importRules(portal, layerPatterns) {
  return {
    "no-restricted-imports": [
      "error",
      { paths: NO_DATA_ACCESS, patterns: [...contractPatterns(portal), ...layerPatterns] },
    ],
  };
}

const NO_REQUESTS = ["fetch", "XMLHttpRequest", "WebSocket", "EventSource"].map((name) => ({
  name,
  message: "packages/ui never makes requests; the container that owns the data does (CLAUDE.md, frontend).",
}));

/**
 * The static rules of packages/ui. Core ESLint rules only (`no-restricted-syntax`,
 * `no-restricted-globals`, `no-restricted-imports`).
 *
 * @param {{ portal?: boolean }} [options] `portal` keeps the ban on the administrative contract
 * (ADR-0003), because the composed import entries replace the one from `contractImportRestrictions`.
 * @returns {import("eslint").Linter.Config[]}
 */
export function uiSourceRules({ portal = false } = {}) {
  return [
    { files: ["**/*.ts", "**/*.tsx"], languageOptions: { parser: tseslint.parser } },
    { files: ["**/*.{ts,tsx,js,mjs}"], rules: { "no-restricted-globals": ["error", ...NO_REQUESTS] } },
    ...SYNTAX_SCOPES.map(({ files, ignores, controls }) => ({
      files,
      ...(ignores === undefined ? {} : { ignores }),
      // A bare ["error"] would inherit the options of the previous entry, so "no controls" is "off".
      rules: { "no-restricted-syntax": controls.length === 0 ? "off" : ["error", ...controls] },
    })),
    { files: ["src/**/*.{ts,tsx}"], rules: importRules(portal, []) },
    { files: ["src/atoms/**"], rules: importRules(portal, [NO_MOLECULES, NO_DOMAIN]) },
    { files: ["src/molecules/**"], rules: importRules(portal, [NO_DOMAIN]) },
  ];
}

/**
 * @param {{ tsconfigRootDir: string, ignores?: string[], portal?: boolean, ui?: boolean, react?: boolean }} options
 * `react` turns on the rules of hooks and the React Compiler rules of eslint-plugin-react-hooks.
 */
export function confiaEslint({ tsconfigRootDir, ignores = [], portal = false, ui = false, react = false }) {
  return tseslint.config(
    { ignores: ["node_modules/**", "dist/**", "coverage/**", "reports/**", ...ignores] },
    js.configs.recommended,
    ...tseslint.configs.strictTypeChecked,
    {
      languageOptions: {
        parserOptions: { projectService: true, tsconfigRootDir },
      },
    },
    contractImportRestrictions({ portal }),
    ...(react ? [reactHooks.configs.flat.recommended] : []),
    ...(ui ? uiSourceRules({ portal }) : []),
    {
      files: ["**/*.js"],
      ...tseslint.configs.disableTypeChecked,
    },
  );
}
