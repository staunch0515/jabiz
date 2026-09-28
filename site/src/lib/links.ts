/** A link is external when it leaves the site's own origin. */
export function isExternal(href: string | undefined, origin = window.location.origin): boolean {
  if (!href) return false
  try {
    return new URL(href, origin).origin !== origin
  } catch {
    return false
  }
}
