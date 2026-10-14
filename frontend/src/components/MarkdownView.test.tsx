import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { expectAccessible } from '../test/axe'
import MarkdownView from './MarkdownView'

// Read from disk: with css: false, Vite hands tests an empty string for style sheets, even with ?raw.
const markdownCss = readFileSync(resolve(import.meta.dirname, '../../packages/ui/src/styles/markdown.css'), 'utf8')

const SAMPLE = [
  '# Title', '## Section', '### Part', '#### Point', '',
  'A paragraph with **bold**, *emphasis*, `code` and a [link](https://example.com).', '',
  '- one', '- two', '', '1. first', '2. second', '', '> quoted', '', '```', 'block', '```',
].join('\n')

describe('MarkdownView', () => {
  it('renders Markdown', () => {
    const { container } = render(<MarkdownView text={'# Title\n\nSome **bold** text and a [link](https://example.com).'} />)
    expect(container.querySelector('h1')?.textContent).toBe('Title')
    expect(container.querySelector('strong')?.textContent).toBe('bold')
    expect(container.querySelector('a')?.getAttribute('href')).toBe('https://example.com')
  })

  it('never renders raw HTML', () => {
    const text = [
      '<script>window.hacked = true</script>',
      '',
      'Inline <img src="x" onerror="window.hacked = true"> image.',
      '',
      '<div onclick="window.hacked = true"><iframe src="https://evil.example"></iframe></div>',
      '',
      '<a href="javascript:alert(1)">raw link</a>',
    ].join('\n')
    const { container } = render(<MarkdownView text={text} />)
    for (const tag of ['script', 'img', 'div div', 'iframe', 'a']) {
      expect(container.querySelector(`[data-testid="markdown-view"] ${tag}`), tag).toBeNull()
    }
    expect(container.innerHTML).not.toContain('onerror')
    expect(container.innerHTML).not.toContain('onclick')
  })

  it('drops script links written in Markdown', () => {
    const { container } = render(<MarkdownView text="[click](javascript:alert(1))" />)
    expect(container.querySelector('a')?.getAttribute('href') ?? '').not.toContain('javascript')
  })

  it('marks the language of a fallback text', () => {
    const { container } = render(<MarkdownView text="Hello" lang="en" />)
    expect(container.querySelector('[data-testid="markdown-view"]')?.getAttribute('lang')).toBe('en')
  })

  describe('under the global preflight (phase 15d)', () => {
    // Tests run without the style sheets (css: false): the Markdown rules of @jabiz/ui are added by hand.
    let sheet: HTMLStyleElement | undefined
    afterEach(() => sheet?.remove())
    const styled = () => {
      sheet = document.createElement('style')
      sheet.textContent = markdownCss
      document.head.append(sheet)
      return render(<MarkdownView text={SAMPLE} />).container
    }

    it('styles headings, lists, links, code and quotes inside the view only', () => {
      const view = styled()
      const style = (selector: string) => getComputedStyle(view.querySelector(selector)!)
      expect(style('h1').fontWeight).toBe('600')
      expect(style('h1').fontSize).toBe('24px')
      expect(style('h2').fontSize).toBe('20.8px')
      expect(style('ul').listStyleType).toBe('disc')
      expect(style('ul').paddingLeft).toBe('24px')
      expect(style('ol').listStyleType).toBe('decimal')
      expect(style('a').textDecorationLine).toBe('underline')
      expect(style('blockquote').borderLeftStyle).toBe('solid')
      expect(markdownCss).toContain('background-color: var(--color-muted)')
      // Theme tokens only: no fixed colours.
      expect(markdownCss).not.toMatch(/#[0-9a-f]{3,6}\b|rgb\(/i)
      // Nothing outside the view is touched.
      const outside = document.createElement('ul')
      document.body.append(outside)
      expect(getComputedStyle(outside).paddingLeft).not.toBe('24px')
      outside.remove()
    })

    it('is accessible, light and dark', async () => {
      const view = styled()
      await expectAccessible(view)
    })
  })
})

