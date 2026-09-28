import { Suspense, use } from 'react'
import type { LocalizedText } from '../api/public-queries'
import { loadMarkdown } from './markdownLoader'

interface Props {
  value: LocalizedText | null | undefined
  className?: string
}

function Rendered(props: Props) {
  // Already loaded (the pages that need it load it with themselves): rendered at once, without a pause.
  const { MarkdownContent } = use(loadMarkdown())
  return <MarkdownContent {...props} />
}

/**
 * Markdown written by editors (design section 9.3): raw HTML is never rendered, images are left out (the site shows
 * only its own files, with alt text checked at publishing), and external links are marked.
 */
export function Markdown(props: Props) {
  return (
    <Suspense fallback={null}>
      <Rendered {...props} />
    </Suspense>
  )
}
