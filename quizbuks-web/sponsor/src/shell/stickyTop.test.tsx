import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { STICKY_TOP_VAR, useStickyTop } from './stickyTop'

function Frame() {
  useStickyTop()
  return null
}

describe('the sticky top (F-F3, WCAG 2.4.11)', () => {
  it('keeps the page\'s scroll padding at the height of the banner and the header, and lets it go when unmounted', () => {
    const sticky = document.createElement('div')
    const header = document.createElement('header')
    header.setAttribute('data-slot', 'app-shell-header')
    sticky.appendChild(header)
    document.body.appendChild(sticky)
    vi.spyOn(sticky, 'getBoundingClientRect').mockReturnValue({ height: 79.5 } as DOMRect)
    const { unmount } = render(<Frame />)
    expect(document.documentElement.style.getPropertyValue(STICKY_TOP_VAR)).toBe('80px')
    unmount()
    expect(document.documentElement.style.getPropertyValue(STICKY_TOP_VAR)).toBe('')
    sticky.remove()
  })

  it('does nothing without the frame\'s header', () => {
    render(<Frame />)
    expect(document.documentElement.style.getPropertyValue(STICKY_TOP_VAR)).toBe('')
  })
})
