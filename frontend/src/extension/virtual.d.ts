/** The extension of the application being built (vite.config.ts: JABIZ_ADMIN_EXTENSION, else none.ts). */
declare module 'virtual:jabiz-extension' {
  import type { AdminExtension } from './api'
  const extension: AdminExtension
  export default extension
}
