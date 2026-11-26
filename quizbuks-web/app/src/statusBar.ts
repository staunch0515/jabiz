/*
 * The status bar follows the appearance (Q113 P11, Q113e): the browser's and the installed app's bar take the
 * page's own background (`theme-color`), white in the light appearance and near-black in the dark, instead of the
 * manifest's brand colour. The platform switches the appearance by the `dark` class on the root element
 * (`applyAppearance`); the colour is read from the page, so the brand's tokens stay the one place it is written.
 */

/** The meta element the browsers read, made once. */
function themeColorMeta(doc: Document): HTMLMetaElement {
  let meta = doc.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (!meta) {
    meta = doc.createElement('meta')
    meta.name = 'theme-color'
    doc.head.appendChild(meta)
  }
  return meta
}

/** Sets the bar's colour to the page's background as it is now; nothing where it cannot be read. */
export function syncStatusBar(doc: Document = document) {
  const colour = getComputedStyle(doc.body).backgroundColor
  if (!colour || colour === 'rgba(0, 0, 0, 0)' || colour === 'transparent') return
  const meta = themeColorMeta(doc)
  if (meta.content !== colour) meta.content = colour
}

/** Keeps the bar's colour with every change of the appearance (and once now). */
export function followAppearanceInStatusBar(doc: Document = document): () => void {
  syncStatusBar(doc)
  const observer = new MutationObserver(() => syncStatusBar(doc))
  observer.observe(doc.documentElement, { attributes: true, attributeFilter: ['class'] })
  return () => observer.disconnect()
}
