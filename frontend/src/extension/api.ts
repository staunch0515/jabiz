import type { LucideIcon } from 'lucide-react'
import type { RouteObject } from 'react-router'
import type { Language } from '../i18n/resources'

/**
 * An application's own pages in the admin frontend (docs/design/12-frontend.md section 9, decision D22). The
 * extension is compiled into the platform's single-page application at build time (`JABIZ_ADMIN_EXTENSION`); it
 * reaches the platform only through `@jabiz/admin`.
 */
export interface AdminExtension {
  /** Pages inside the signed-in frame. Paths are absolute and must not reuse the platform's own. */
  routes?: RouteObject[]
  /** Menu entries after the server's menus; labels are keys of {@link messages}. */
  menu?: ExtensionMenuItem[]
  /** The extension's texts, in the i18next namespace {@link EXTENSION_NAMESPACE}, per UI language. */
  messages?: Partial<Record<Language, Record<string, unknown>>>
  /** Where a signed-in user lands; the data catalog when absent. */
  home?: string
}

export interface ExtensionMenuItem {
  key: string
  /** Key of the extension's messages. */
  label: string
  path?: string
  /** A lucide icon component (`import { Boxes } from 'lucide-react'`; `icon: Boxes`), not an element. */
  icon?: LucideIcon
  /**
   * Shown only to users who hold it ("*" holds all). Hiding is navigation, not access: the server checks every call
   * the page makes.
   */
  permission?: string
  children?: ExtensionMenuItem[]
}

/** The i18next namespace of extension texts: `useTranslation(EXTENSION_NAMESPACE)`. */
export const EXTENSION_NAMESPACE = 'app'

/** Declares an extension; the identity function, for type checking of the object. */
export function defineExtension(extension: AdminExtension): AdminExtension {
  return extension
}
