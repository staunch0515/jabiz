import type { ReactNode } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import type { LocalizedText } from '../api/public-queries'
import { useLocale, useT } from '../i18n/locale'
import { isExternal } from '../lib/links'
import { pick } from '../lib/localized'

function ExternalAwareLink({ href, children }: { href?: string; children?: ReactNode }) {
  const t = useT()
  if (!isExternal(href)) return <a href={href}>{children}</a>
  return (
    <a href={href} rel="noopener noreferrer">
      {children}
      <span className="visually-hidden"> {t('common.externalLink')}</span>
      <span aria-hidden="true"> ↗</span>
    </a>
  )
}

// Headings inside content never compete with the page's own: the page has one h1 and its sections are h2.
const components: Components = {
  h1: ({ children }) => <h3>{children}</h3>,
  h2: ({ children }) => <h3>{children}</h3>,
  h3: ({ children }) => <h4>{children}</h4>,
  h4: ({ children }) => <h4>{children}</h4>,
  h5: ({ children }) => <h4>{children}</h4>,
  h6: ({ children }) => <h4>{children}</h4>,
  a: ({ href, children }) => <ExternalAwareLink href={href}>{children}</ExternalAwareLink>,
}

interface Props {
  value: LocalizedText | null | undefined
  className?: string
}

/**
 * Markdown written by editors (design section 9.3): raw HTML is never rendered, images are left out (the site shows
 * only its own files, with alt text checked at publishing), and external links are marked.
 */
export function Markdown({ value, className = 'prose' }: Props) {
  const locale = useLocale()
  const picked = pick(value, locale)
  if (!picked) return null
  return (
    <div className={className} lang={picked.fallback ? picked.lang : undefined}>
      <ReactMarkdown skipHtml disallowedElements={['img']} unwrapDisallowed components={components}>
        {picked.text}
      </ReactMarkdown>
    </div>
  )
}
