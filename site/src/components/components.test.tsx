import { act, fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { renderIn } from '../test/render'
import { FilterBar } from './FilterBar'
import { LocalizedText } from './LocalizedText'
import { Markdown } from './Markdown'
import { loadMarkdown } from './markdownLoader'
import { ReflectionBlock } from './ReflectionBlock'
import { ResponsiveImage } from './ResponsiveImage'
import { VideoEmbed } from './VideoEmbed'

describe('LocalizedText', () => {
  it('shows the interface language without a lang attribute', async () => {
    renderIn(<LocalizedText as="h1" value={{ en: 'Home', zh: '家' }} />, { locale: 'zh' })
    const heading = await screen.findByRole('heading', { name: '家' })
    expect(heading).not.toHaveAttribute('lang')
  })

  it('marks a fallback with the language it is in', async () => {
    renderIn(<LocalizedText as="h1" value={{ en: 'Home' }} />, { locale: 'ja' })
    expect(await screen.findByRole('heading', { name: 'Home' })).toHaveAttribute('lang', 'en')
  })

  it('renders nothing without text', async () => {
    const { container } = renderIn(<LocalizedText value={{ en: ' ' }} />)
    await act(async () => {})
    expect(container.textContent).toBe('')
  })
})

describe('Markdown (design section 9.3)', () => {
  // The renderer is a chunk of its own; the first import of it (and of react-markdown) is slow in the test runner.
  beforeAll(async () => {
    await loadMarkdown()
  })

  it('never renders raw HTML', async () => {
    const { container } = renderIn(
      <Markdown value={{ en: 'Hello <script>alert(1)</script><img src=x onerror=alert(1)> <b>bold</b> world' }} />,
    )
    await screen.findByText(/Hello/)
    expect(container.querySelector('script, img, b')).toBeNull()
    expect(container.innerHTML).not.toContain('onerror')
  })

  it('leaves out Markdown images: the site shows only its own files', async () => {
    const { container } = renderIn(<Markdown value={{ en: 'See ![a tracker](https://tracker.example/p.gif) here' }} />)
    await screen.findByText(/See/)
    expect(container.querySelector('img')).toBeNull()
  })

  it('marks external links and keeps no opener', async () => {
    renderIn(<Markdown value={{ en: 'Read [the report](https://example.org/r) and [our method](/en/method).' }} />)
    const external = await screen.findByRole('link', { name: /the report/ })
    expect(external).toHaveAttribute('rel', 'noopener noreferrer')
    expect(external).toHaveTextContent('(external link)')
    const internal = screen.getByRole('link', { name: 'our method' })
    expect(internal).not.toHaveAttribute('rel')
  })

  it('does not keep script links', async () => {
    renderIn(<Markdown value={{ en: '[click](javascript:alert(1))' }} />)
    const link = await screen.findByText('click')
    expect(link.closest('a')?.getAttribute('href') ?? '').not.toContain('javascript')
  })

  it('turns content headings into headings below the page sections', async () => {
    renderIn(<Markdown value={{ en: '# Big\n\n## Medium\n\n### Small' }} />)
    expect(await screen.findByRole('heading', { name: 'Big', level: 3 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Medium', level: 3 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Small', level: 4 })).toBeInTheDocument()
  })

  it('marks the language of a fallback', async () => {
    const { container } = renderIn(<Markdown value={{ en: 'Only English' }} />, { locale: 'zh' })
    await screen.findByText('Only English')
    expect(container.querySelector('.prose')).toHaveAttribute('lang', 'en')
  })
})

describe('VideoEmbed (design section 9.3)', () => {
  it('loads nothing from the player host until asked to', async () => {
    const { container } = renderIn(
      <VideoEmbed provider="YOUTUBE" videoId="dQw4w9WgXcQ" title="Home" posterFileId="f1" transcript={{ en: 'Words' }} />,
      { locale: 'ja' },
    )
    const play = await screen.findByRole('button', { name: /Home/ })
    expect(container.querySelector('iframe')).toBeNull()
    expect(container.innerHTML).not.toContain('youtube')
    // The poster is our own file.
    expect(container.querySelector('img')?.getAttribute('src')).toBe('/api/public/files/f1/w640')
    expect(play).toHaveAccessibleDescription(/YouTube/)

    await userEvent.click(play)
    const frame = container.querySelector('iframe')!
    expect(frame.src).toMatch(/^https:\/\/www\.youtube-nocookie\.com\/embed\/dQw4w9WgXcQ\?/)
    expect(frame.src).toContain('hl=ja')
    expect(frame.title).toBeTruthy()
    expect(document.activeElement).toBe(frame)
  })

  it('shows the transcript on request', async () => {
    renderIn(<VideoEmbed provider="VIMEO" videoId="76979871" title="Home" transcript={{ en: 'Every evening the rice cooker clicks.' }} />)
    const summary = await screen.findByText('Read the transcript')
    expect(summary.closest('details')).not.toHaveAttribute('open')
    fireEvent.click(summary)
    expect(summary.closest('details')).toHaveAttribute('open')
  })

  it('shows nothing for a video it may not embed', async () => {
    const { container } = renderIn(<VideoEmbed provider="OTHER" videoId="dQw4w9WgXcQ" title="Home" />)
    await act(async () => {})
    expect(container.querySelector('button, iframe')).toBeNull()
  })
})

describe('ResponsiveImage (design sections 9.3, 16)', () => {
  it('offers the width variants and falls back to the original when one is missing', async () => {
    const { container } = renderIn(<ResponsiveImage fileId="f2" alt="A kitchen" ratio="wide" sizes="100vw" />)
    await screen.findByAltText('A kitchen')
    const img = container.querySelector('img')!
    expect(img.getAttribute('srcset')).toContain('/api/public/files/f2/w1280 1280w')
    expect(img).toHaveAttribute('loading', 'lazy')
    expect(img).toHaveAttribute('width')
    fireEvent.error(img)
    expect(img.getAttribute('src')).toBe('/api/public/files/f2')
    expect(img).not.toHaveAttribute('srcset')
  })

  it('is a decorative frame without a file', async () => {
    const { container } = renderIn(<ResponsiveImage fileId={null} alt="" ratio="portrait" sizes="10vw" placeholder="Aiko" />)
    await act(async () => {})
    expect(container.querySelector('img')).toBeNull()
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true')
    expect(container.textContent).toBe('A')
  })
})

describe('ReflectionBlock', () => {
  it('shows the questions that have answers, and nothing without any', async () => {
    const first = renderIn(<ReflectionBlock surprised={{ en: 'Nobody filmed a door.' }} assumed={null} learned={{ en: 'Home is a routine.' }} />)
    expect(await screen.findByRole('heading', { name: 'What surprised us?', level: 3 })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'What did we initially assume?' })).toBeNull()
    first.unmount()
    const { container } = renderIn(<ReflectionBlock surprised={null} assumed={{ en: '' }} learned={null} />)
    await act(async () => {})
    expect(container.textContent).toBe('')
  })
})

describe('FilterBar (design section 9.3)', () => {
  it('reports each change and announces the result', async () => {
    const onChange = vi.fn()
    const onClear = vi.fn()
    renderIn(
      <FilterBar
        label="Filter stories"
        groups={[
          { name: 'place', legend: 'Place', options: [{ value: 'japan', label: 'Japan', icon: '🇯🇵' }, { value: 'poland', label: 'Poland' }] },
          { name: 'media', legend: 'Media', options: [] },
        ]}
        selected={{ place: ['japan'] }}
        onChange={onChange}
        onClear={onClear}
        status="3 stories"
      />,
    )
    expect(await screen.findByRole('search', { name: 'Filter stories' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Place' })).toBeInTheDocument()
    // A group without options is not shown.
    expect(screen.queryByRole('group', { name: 'Media' })).toBeNull()
    expect(screen.getByRole('checkbox', { name: 'Japan' })).toBeChecked()
    await userEvent.click(screen.getByRole('checkbox', { name: 'Poland' }))
    expect(onChange).toHaveBeenLastCalledWith('place', ['japan', 'poland'])
    await userEvent.click(screen.getByRole('checkbox', { name: 'Japan' }))
    expect(onChange).toHaveBeenLastCalledWith('place', [])
    expect(screen.getByRole('status')).toHaveTextContent('3 stories')
    await userEvent.click(screen.getByRole('button', { name: 'Clear all filters' }))
    expect(onClear).toHaveBeenCalled()
  })
})

describe('ResponsiveImage with another file', () => {
  it('tries the variants again', async () => {
    const { container, rerender } = await import('@testing-library/react').then(({ render }) =>
      render(<ResponsiveImage fileId="a" alt="A" ratio="square" sizes="10vw" />),
    )
    fireEvent.error(container.querySelector('img')!)
    expect(container.querySelector('img')!.getAttribute('src')).toBe('/api/public/files/a')
    rerender(<ResponsiveImage fileId="b" alt="B" ratio="square" sizes="10vw" />)
    expect(container.querySelector('img')!.getAttribute('src')).toBe('/api/public/files/b/w640')
  })
})
