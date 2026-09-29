import type { i18n as I18n } from 'i18next'
import { EXTENSION_NAMESPACE, type AdminExtension } from './api'

/** Adds the extension's texts as their own namespace, so they can never replace the platform's. */
export function registerExtensionMessages(i18n: I18n, extension: AdminExtension) {
  for (const [language, messages] of Object.entries(extension.messages ?? {})) {
    if (messages) i18n.addResourceBundle(language, EXTENSION_NAMESPACE, messages, true, true)
  }
}
