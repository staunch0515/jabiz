import { relative, resolve } from 'node:path'

const PLATFORM_ONLY_THROUGH_ADMIN = {
  group: ['**/frontend/**', 'virtual:jabiz-extension'],
  message: 'An extension uses the platform only through @jabiz/admin (decision D22).',
}

const NO_ANT_DESIGN = {
  group: ['antd', 'antd/*', '@ant-design/*'],
  message: 'Ant Design leaves the platform (decision D34): use the components @jabiz/admin exports (@jabiz/ui).',
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
 * Extensions that may still import Ant Design, relative to the repository root: the demo application's, until it
 * moves to @jabiz/ui in phase 15c.
 */
export const ANT_DESIGN_EXEMPT = ['backend/app/admin-extension']

const repositoryRoot = resolve(import.meta.dirname, '../..')

/** The rules for the extension in `dir`. */
export function extensionRulesFor(dir) {
  const path = relative(repositoryRoot, resolve(dir)).split('\\').join('/')
  return ANT_DESIGN_EXEMPT.includes(path) ? rules([PLATFORM_ONLY_THROUGH_ADMIN]) : extensionRules
}
