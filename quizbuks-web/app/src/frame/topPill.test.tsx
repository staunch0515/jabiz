import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import '../messages'

const update = vi.fn()
let waiting: (() => void) | null = null
vi.mock('@jabiz/client', async (original) => ({
  ...(await original<typeof import('@jabiz/client')>()),
  useServiceWorkerUpdate: () => waiting,
}))

const { UpdatePill } = await import('./TopPill')

afterEach(() => {
  cleanup()
  waiting = null
  update.mockReset()
})

describe('a new version of the app (2026-11-28: a pill, x.com\'s "Show new posts", not a band)', () => {
  it('shows nothing while no new version waits', () => {
    render(<UpdatePill />)
    expect(screen.queryByTestId('app-update')).toBeNull()
  })

  it('floats a pill under the header, taking no room of the page; a tap takes the new version', async () => {
    waiting = update
    render(<UpdatePill />)
    const pill = screen.getByTestId('app-update')
    expect(pill).toHaveTextContent('A new version · tap to update')
    expect(screen.getByTestId('app-update-place')).toHaveClass('sticky', 'h-0', 'pointer-events-none')
    expect(screen.getByTestId('app-update-place')).toHaveAttribute('role', 'status')
    await userEvent.click(pill)
    expect(update).toHaveBeenCalledTimes(1)
  })
})
