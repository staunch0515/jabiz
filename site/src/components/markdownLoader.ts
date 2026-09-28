// The Markdown renderer is most of a page's JavaScript: it is loaded apart from the first screen (design section
// 9.4). Pages that show editors' texts load it together with themselves (routes.tsx), so that their texts never
// appear after the rest of the page; the home page's text is below its first screen.

type Renderer = typeof import('./MarkdownContent')

/** A promise React's `use` can read at once once it is settled (it looks at `status` and `value`). */
type Tracked<T> = Promise<T> & { status?: 'pending' | 'fulfilled' | 'rejected'; value?: T; reason?: unknown }

let loading: Tracked<Renderer> | null = null

export function loadMarkdown(): Promise<Renderer> {
  if (!loading) {
    const promise: Tracked<Renderer> = import('./MarkdownContent')
    promise.status = 'pending'
    promise.then(
      (value) => {
        promise.status = 'fulfilled'
        promise.value = value
      },
      (reason: unknown) => {
        promise.status = 'rejected'
        promise.reason = reason
        // A failed download (a dropped connection, a new release) is tried again the next time.
        loading = null
      },
    )
    loading = promise
  }
  return loading
}

/** Starts loading the renderer without waiting for a page to need it. */
export function preloadMarkdown(): void {
  loadMarkdown().catch(() => {})
}
