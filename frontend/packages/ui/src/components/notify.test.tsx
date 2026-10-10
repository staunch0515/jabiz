import { act, render, screen } from '@testing-library/react'
import { i18n } from '@jabiz/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { expectAccessible } from '../test/axe'
import { AppearanceProvider } from './appearance'
import { notify } from './notify'
import { Toaster } from './ui/sonner'

describe('notify', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })
  afterEach(() => {
    act(() => notify.dismiss())
  })

  it('shows messages in the Toaster, in a named region screen readers follow', async () => {
    render(
      <AppearanceProvider>
        <Toaster />
      </AppearanceProvider>,
    )
    act(() => {
      notify.success('Saved.')
      notify.error('The import failed.', { description: 'Row 3: no such carrier.' })
    })
    expect(await screen.findByText('Saved.')).toBeInTheDocument()
    expect(screen.getByText('Row 3: no such carrier.')).toBeInTheDocument()
    const region = screen.getByRole('region', { name: /Notifications/ })
    expect(region).toHaveAttribute('aria-live', 'polite')
    await expectAccessible()
  })
})
