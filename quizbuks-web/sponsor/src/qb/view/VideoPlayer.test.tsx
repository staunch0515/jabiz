import { expectAccessible } from '@jabiz/ui/testing'
import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import './messages'
import { VideoPlayer } from './VideoPlayer'

const FILE = 'https://cdn.example.com/clip.mp4'
const PAGE = 'https://player.example.com/watch/42'
const YOUTUBE = 'https://www.youtube-nocookie.com/embed/abcdefghijk'
const VIMEO = 'https://player.vimeo.com/video/123456'
const BILIBILI = 'https://player.bilibili.com/player.html?bvid=BV1xx411c7mD&autoplay=0'

const request = vi.fn(() => Promise.resolve())
const exit = vi.fn(() => Promise.resolve())

/** The browser's Fullscreen API, as far as the player needs it. */
function browser(options: { elements?: boolean; video?: boolean } = {}) {
  const { elements = true, video = false } = options
  Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, value: elements })
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, writable: true, value: null })
  Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: exit })
  Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, value: request })
  if (video) {
    Object.defineProperty(HTMLVideoElement.prototype, 'webkitEnterFullscreen', { configurable: true, value: vi.fn() })
  } else {
    delete (HTMLVideoElement.prototype as { webkitEnterFullscreen?: unknown }).webkitEnterFullscreen
  }
}

beforeEach(() => {
  request.mockClear()
  exit.mockClear()
  browser()
})
afterEach(cleanup)

describe('VideoPlayer full-screen key (F-S16)', () => {
  it('puts a full-screen key at the bottom right of a video file, which goes full screen with its container', async () => {
    const { container } = render(<VideoPlayer player={FILE} title="Clip" />)
    const key = screen.getByTestId('video-fullscreen')
    expect(key).toHaveAccessibleName('Full screen')
    expect(key).toHaveClass('right-2')
    // The browser's own key is taken away where it can be, so that there are not two.
    expect(container.querySelector('video')).toHaveAttribute('controlslist', 'nofullscreen')
    await userEvent.click(key)
    expect(request).toHaveBeenCalledTimes(1)
    // The element that goes full screen holds the video and the key, so the key stays in full screen.
    const element = (request.mock.contexts as HTMLElement[])[0]
    expect(element).toContainElement(container.querySelector('video'))
    expect(element).toContainElement(key)
    await expectAccessible(container)
  })

  it('reads "Exit full screen" in full screen, and leaves it by the same key', async () => {
    render(<VideoPlayer player={FILE} title="Clip" />)
    await userEvent.click(screen.getByTestId('video-fullscreen'))
    const element = (request.mock.contexts as HTMLElement[])[0]
    act(() => {
      ;(document as { fullscreenElement: Element | null }).fullscreenElement = element
      document.dispatchEvent(new Event('fullscreenchange'))
    })
    const key = screen.getByTestId('video-fullscreen')
    expect(key).toHaveAccessibleName('Exit full screen')
    expect(key).toHaveAttribute('data-active', 'true')
    await userEvent.click(key)
    expect(exit).toHaveBeenCalledTimes(1)
    // Esc (the browser's own way out) is heard as well.
    act(() => {
      ;(document as { fullscreenElement: Element | null }).fullscreenElement = null
      document.dispatchEvent(new Event('fullscreenchange'))
    })
    expect(screen.getByTestId('video-fullscreen')).toHaveAccessibleName('Full screen')
  })

  it('is on another site\'s page and on the sites other than YouTube and Vimeo, whose players bring their own', () => {
    const { rerender } = render(<VideoPlayer player={PAGE} title="Page" />)
    expect(screen.getByTestId('video-fullscreen')).toBeInTheDocument()
    rerender(<VideoPlayer player={BILIBILI} title="Bilibili" />)
    expect(screen.getByTestId('video-fullscreen')).toBeInTheDocument()
    rerender(<VideoPlayer player={YOUTUBE} title="YouTube" />)
    expect(screen.queryByTestId('video-fullscreen')).toBeNull()
    rerender(<VideoPlayer player={VIMEO} title="Vimeo" />)
    expect(screen.queryByTestId('video-fullscreen')).toBeNull()
  })

  it('says it in every language', async () => {
    const { i18n } = await import('@jabiz/client')
    render(<VideoPlayer player={FILE} title="Clip" />)
    try {
      await act(() => i18n.changeLanguage('zh'))
      expect(screen.getByTestId('video-fullscreen')).toHaveAccessibleName('全屏')
      await act(() => i18n.changeLanguage('ja'))
      expect(screen.getByTestId('video-fullscreen')).toHaveAccessibleName('全画面')
    } finally {
      await act(() => i18n.changeLanguage('en'))
    }
  })

  it('shows no key where the browser cannot make an element full screen, except an iPhone\'s own video', async () => {
    browser({ elements: false })
    const { rerender } = render(<VideoPlayer player={FILE} title="Clip" />)
    expect(screen.queryByTestId('video-fullscreen')).toBeNull()
    rerender(<VideoPlayer player={PAGE} title="Page" />)
    expect(screen.queryByTestId('video-fullscreen')).toBeNull()

    // An iPhone: no element goes full screen, but its <video> does.
    browser({ elements: false, video: true })
    rerender(<VideoPlayer player={FILE} title="Clip again" />)
    const key = screen.getByTestId('video-fullscreen')
    await userEvent.click(key)
    expect(request).not.toHaveBeenCalled()
    expect((HTMLVideoElement.prototype as unknown as { webkitEnterFullscreen: ReturnType<typeof vi.fn> })
      .webkitEnterFullscreen).toHaveBeenCalledTimes(1)
    rerender(<VideoPlayer player={PAGE} title="Page again" />)
    expect(screen.queryByTestId('video-fullscreen')).toBeNull()
  })

  it('leaves the player as it was when the browser refuses', async () => {
    request.mockImplementationOnce(() => Promise.reject(new Error('not allowed')))
    render(<VideoPlayer player={PAGE} title="Page" />)
    await userEvent.click(screen.getByTestId('video-fullscreen'))
    expect(screen.getByTestId('video-fullscreen')).toHaveAccessibleName('Full screen')
  })
})
