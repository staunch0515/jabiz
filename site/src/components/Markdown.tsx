import { lazy, Suspense } from 'react'
import type { LocalizedText } from '../api/public-queries'
import { loadMarkdown } from './markdownLoader'

const Content = lazy(() => loadMarkdown().then((module) => ({ default: module.MarkdownContent })))

interface Props {
  value: LocalizedText | null | undefined
  className?: string
}

/**
 * Markdown written by editors (design section 9.3): raw HTML is never rendered, images are left out (the site shows
 * only its own files, with alt text checked at publishing), and external links are marked.
 */
export function Markdown(props: Props) {
  return (
    <Suspense fallback={null}>
      <Content {...props} />
    </Suspense>
  )
}
