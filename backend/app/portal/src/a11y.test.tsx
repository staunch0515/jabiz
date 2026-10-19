import { i18n } from '@jabiz/client'
import { MailUnsubscribe } from '@jabiz/ui'
import { expectAccessible } from '@jabiz/ui/testing'
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import './messages'

/**
 * The platform's accessibility check from an application's SPA (`@jabiz/ui/testing`, ROADMAP 16v-1): the helper runs
 * on the portal's own Vitest and vitest-axe (the preset of `@jabiz/client/vite` points them at the portal's copies),
 * in the light and the dark appearance.
 */
beforeEach(async () => {
  await act(() => i18n.changeLanguage('en'))
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('[]', {
    status: 200, headers: { 'Content-Type': 'application/json' },
  }))
})

afterEach(() => vi.restoreAllMocks())

describe('the portal with @jabiz/ui/testing', () => {
  it('checks its unsubscribe page', async () => {
    render(<MailUnsubscribe brand="Portal" search="" />)
    expect(screen.getByTestId('unsubscribe-state').dataset.state).toBe('invalid')
    await expectAccessible()
  })

  it('fails on a violation, as it should', async () => {
    render(<img src="data:," />)
    await expect(expectAccessible()).rejects.toThrow(/axe violations/)
  })
})
