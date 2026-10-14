import Markdown from 'react-markdown'

export interface MarkdownViewProps {
  text: string
  /** The text's language, when it is not the interface language (screen readers read it in that language). */
  lang?: string
}

/**
 * Markdown rendered for display. Raw HTML in the text is dropped, never rendered (skipHtml, no rehype-raw), and
 * react-markdown's URL filter removes javascript: and similar links (docs/design/16-content-authoring.md section 1.3,
 * decision D20). Styled by `markdown.css` (part of `theme.css`) within `.markdown-view` only.
 */
export function MarkdownView({ text, lang }: MarkdownViewProps) {
  return (
    <div className="markdown-view" lang={lang} data-testid="markdown-view">
      <Markdown skipHtml>{text}</Markdown>
    </div>
  )
}
