import { i18n } from '@jabiz/client'
import { APP } from '../messages'
import { QB } from '../qb/messages'
import { QBVIEW } from '../qb/view/messages'

/** The dotted keys (`detail.pickCount`) of a namespace's English texts. */
function keysOf(namespace: string): string[] {
  const keys: string[] = []
  const walk = (node: unknown, path: string[]) => {
    if (node && typeof node === 'object') {
      for (const [key, value] of Object.entries(node)) walk(value, [...path, key])
    } else if (path.length > 1) {
      keys.push(path.join('.'))
    }
  }
  walk(i18n.getResourceBundle('en', namespace), [])
  return keys
}

/**
 * The message keys of the app's namespaces that appear verbatim in the text, for a test that a page shows texts and
 * not keys looked up in the wrong namespace (a missing key is shown as the key itself).
 */
export function rawKeysIn(text: string): string[] {
  return [APP, QB, QBVIEW].flatMap(keysOf).filter((key) => text.includes(key))
}
