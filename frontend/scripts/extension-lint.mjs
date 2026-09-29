/** Lint rules an admin extension gets on top of the platform's (decision D22): the platform only via @jabiz/admin. */
export const extensionRules = {
  files: ['**/*.{ts,tsx}'],
  rules: {
    'no-restricted-imports': ['error', {
      patterns: [{
        group: ['**/frontend/**', 'virtual:jabiz-extension'],
        message: 'An extension uses the platform only through @jabiz/admin (decision D22).',
      }],
    }],
  },
}
