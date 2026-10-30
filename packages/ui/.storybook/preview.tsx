import type { Decorator, Preview } from "@storybook/react-vite";
import { I18nextProvider } from "react-i18next";
import { axeRunOptions } from "../src/a11y/policy";
import { createUiI18n } from "../src/i18n/create-ui-i18n";
import "../src/tokens/index.css";

const i18n = createUiI18n();

// The theme is the data-theme attribute of the root element (docs/ui-ux/01 section 6), set by the
// toolbar below. Stories render one theme at a time, never side by side.
const withTheme: Decorator = (Story, context) => {
  document.documentElement.dataset.theme = context.globals["theme"] === "dark" ? "dark" : "light";
  return <Story />;
};

const withI18n: Decorator = (Story) => (
  <I18nextProvider i18n={i18n}>
    <Story />
  </I18nextProvider>
);

const preview: Preview = {
  decorators: [withTheme, withI18n],
  initialGlobals: { theme: "light" },
  globalTypes: {
    theme: {
      description: "Color theme",
      toolbar: {
        title: "Theme",
        icon: "circlehollow",
        dynamicTitle: true,
        items: [
          { value: "light", title: "Light" },
          { value: "dark", title: "Dark" },
        ],
      },
    },
  },
  parameters: {
    // The same tags the browser gate evaluates; the panel is for whoever reviews a story by hand.
    a11y: { options: axeRunOptions },
  },
};

export default preview;
