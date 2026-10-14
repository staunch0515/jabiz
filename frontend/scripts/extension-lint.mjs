const PLATFORM_ONLY_THROUGH_ADMIN = {
  group: ['**/frontend/**', 'virtual:jabiz-extension'],
  message: 'An extension uses the platform only through @jabiz/admin (decision D22).',
}

/**
 * Ant Design left the platform (decision D34, phase 15d). The admin frontend refuses it with this same pattern
 * (eslint.config.js); the message names the replacement, clearer than a module that cannot be found.
 */
export const NO_ANT_DESIGN = {
  group: ['antd', 'antd/*', '@ant-design/*'],
  message: 'Ant Design left the platform (decision D34): use @jabiz/ui (in an extension, through @jabiz/admin).',
}

function rules(patterns) {
  return {
    files: ['**/*.{ts,tsx}'],
    rules: { 'no-restricted-imports': ['error', { patterns }] },
  }
}

/**
 * Lint rules an admin extension gets on top of the platform's (decisions D22, D34): the platform only via
 * @jabiz/admin, and no Ant Design.
 */
export const extensionRules = rules([PLATFORM_ONLY_THROUGH_ADMIN, NO_ANT_DESIGN])

/**
 * The rules for an extension (called with its directory): the same for every extension (the demo extension's
 * exemption from the Ant Design rule ended when it moved to @jabiz/ui, phase 15c-2).
 */
export function extensionRulesFor() {
  return extensionRules
}
