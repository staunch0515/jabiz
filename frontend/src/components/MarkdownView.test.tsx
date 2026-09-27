import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import MarkdownView from './MarkdownView'

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
})
