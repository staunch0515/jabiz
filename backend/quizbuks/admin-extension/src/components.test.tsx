import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AppearanceProvider } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { EmptyState, StatusBadge } from './components'

describe('the shared pieces of the administration (Q53c)', () => {
  it('colour a status badge by its meaning and keep its code', async () => {
    const { container } = render(
      <AppearanceProvider>
        <StatusBadge code="LIVE" label="Live" testId="live" />
        <StatusBadge code="PENDING_REVIEW" label="Waiting" testId="waiting" />
        <StatusBadge code="REJECTED" label="Rejected" testId="rejected" />
        <StatusBadge code="ENDED" label="Ended" testId="ended" />
      </AppearanceProvider>,
    )
    expect(screen.getByTestId('live')).toHaveAttribute('data-status', 'LIVE')
    expect(screen.getByTestId('live').className).toMatch(/bg-success/)
    expect(screen.getByTestId('waiting').className).toMatch(/bg-warning/)
    expect(screen.getByTestId('rejected').className).toMatch(/bg-destructive/)
    expect(screen.getByTestId('ended').className).not.toMatch(/bg-(success|warning|destructive)/)
    await expectAccessible(container)
  })

  it('show an empty list with a decorative drawing, its line and a hint', async () => {
    const { container } = render(<EmptyState art="done" title="Nothing to review" hint="New requests show here." />)
    const empty = screen.getByTestId('empty-state')
    expect(empty).toHaveTextContent('Nothing to review')
    expect(empty).toHaveTextContent('New requests show here.')
    const art = empty.querySelector('svg')
    expect(art).toHaveAttribute('aria-hidden')
    expect(art).toHaveAttribute('data-art', 'done')
    await expectAccessible(container)
  })
})
