import { HANDLE_PATTERN } from './api'

/*
 * F-F9 (troubleshooting T10): sponsors paste their own website's address into the link name. The link name is the
 * page's name on QuizBuks (`/taker/s/<name>`); the website goes in the field below. When the link name looks like an
 * address, the editor says so and offers a name taken from the domain, which the person applies with one click.
 * Nothing is rewritten by itself, and the rule of a link name ({@link HANDLE_PATTERN}) does not change.
 */

/**
 * The second-level labels under a country's code that are not the business's own name (`co.jp`, `com.cn`, `co.uk`,
 * `or.jp`, ...): the name is the label before them.
 */
const SECOND_LEVEL = new Set(['co', 'com', 'net', 'org', 'or', 'ne', 'ac', 'ad', 'go', 'gov', 'edu', 'ed', 'gr',
  'lg', 'ltd', 'plc'])

/** What a link name that looks like an address may become. */
export interface AddressInHandle {
  /** The name taken from the domain; null when none passes the rule (too short, or a domain not in Latin letters). */
  handle: string | null
  /** The address as a website: with `https://` when it was typed without a scheme. */
  website: string
}

/** Whether the text is an address rather than a name: a scheme, `www.`, or a dotted host with no spaces. */
function looksLikeAddress(text: string): boolean {
  if (/\s/.test(text)) return false
  if (/^https?:\/\//i.test(text) || /^www\./i.test(text)) return true
  // A host with a dot and a top-level domain of letters (`aini-helmet.com`, `例子.中国`), maybe a path after it.
  return /^[^/?#.]+(\.[^/?#.]+)*\.[\p{L}]{2,}(?:[/?#].*)?$/u.test(text)
}

/**
 * The address a link name holds, and the name its domain suggests; null when the text is not an address (an ordinary
 * name, valid or not, is left to the usual check).
 */
export function addressInHandle(input: string): AddressInHandle | null {
  const text = input.trim()
  if (!text || HANDLE_PATTERN.test(text) || !looksLikeAddress(text)) return null
  const website = /^https?:\/\//i.test(text) ? text : `https://${text}`
  let host: string
  try {
    host = new URL(website).hostname.toLowerCase()
  } catch {
    return null
  }
  // A domain not in Latin letters (`例子.中国`, punycode `xn--`): no name to take from it.
  const typedHost = text.replace(/^https?:\/\//i, '').split(/[/?#:]/)[0]
  if ([...typedHost].some((c) => c.charCodeAt(0) > 0x7f) || host.split('.').some((label) => label.startsWith('xn--'))) {
    return { handle: null, website }
  }
  const labels = host.split('.').filter(Boolean)
  if (labels[0] === 'www') labels.shift()
  labels.pop() // the top-level domain
  if (labels.length > 1 && SECOND_LEVEL.has(labels[labels.length - 1])) labels.pop()
  const name = labels.length ? labels[labels.length - 1] : ''
  return { handle: HANDLE_PATTERN.test(name) ? name : null, website }
}
