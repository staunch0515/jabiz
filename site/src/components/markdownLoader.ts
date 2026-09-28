// The Markdown renderer is most of a page's JavaScript: it is loaded apart from the first screen (design section
// 9.4), right after the site starts, so that it is there by the time a page needs it.
export const loadMarkdown = () => import('./MarkdownContent')

/** Starts loading the renderer without waiting for a page to need it. */
export function preloadMarkdown(): void {
  void loadMarkdown()
}
