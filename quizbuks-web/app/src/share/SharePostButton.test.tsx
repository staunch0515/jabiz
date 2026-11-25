import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import '../messages'
import { postUrl, SharePostButton } from './SharePostButton'

afterEach(() => {
  vi.restoreAllMocks()
  Reflect.deleteProperty(navigator, 'share')
  Reflect.deleteProperty(navigator, 'clipboard')
})

describe("a home post's share button (Q113b-1)", () => {
  it("shares the post's page on this site", () => {
    expect(postUrl('pub-1', 'https://example.test')).toBe('https://example.test/p/pub-1')
  })

  it("opens the device's own sheet where it has one", async () => {
    const share = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { value: share, configurable: true })
    render(<SharePostButton publicationId="pub-1" name="Capitals week" />)
    await userEvent.click(screen.getByRole('button', { name: 'Share' }))
    expect(share).toHaveBeenCalledWith({ title: 'Capitals week', url: postUrl('pub-1') })
  })

  it('copies the link where there is no sheet', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<SharePostButton publicationId="pub-1" name="Capitals week" />)
    await userEvent.click(screen.getByRole('button', { name: 'Share' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(postUrl('pub-1')))
  })
})
