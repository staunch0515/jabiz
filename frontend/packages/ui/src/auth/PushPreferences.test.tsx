import { configureClient, i18n, session, type PushSettings } from '@jabiz/client'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider } from '../components/appearance'
import { expectAccessible } from '@jabiz/ui/testing'
import { PushPreferences } from './index'

const settings: PushSettings = {
  enabled: true,
  webPushKey: 'key',
  templates: [
    { template: 'shop.shipped', name: 'Shipment notices', subscribed: true, otherEntry: false },
    { template: 'shop.offers', name: 'Offers', subscribed: false, otherEntry: false },
  ],
  devices: [],
}

const user = () => userEvent.setup()

beforeEach(async () => {
  configureClient({ entry: 'portal' })
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
})

function renderBlock(props: Parameters<typeof PushPreferences>[0]) {
  return render(<AppearanceProvider><PushPreferences supported={() => true} {...props} /></AppearanceProvider>)
}

describe('PushPreferences (decision D69)', () => {
  it('turns this device and each kind on and off', async () => {
    const enable = vi.fn(async () => 'enabled' as const)
    const disable = vi.fn(async () => undefined)
    const save = vi.fn(async () => undefined)
    renderBlock({ load: async () => settings, enabledHere: async () => false, enable, disable, save })
    const device = await screen.findByRole('switch', { name: 'Notifications on this device' })
    expect(device).not.toBeChecked()
    expect(screen.getByRole('switch', { name: 'Offers' })).not.toBeChecked()
    await expectAccessible()

    await user().click(device)
    expect(enable).toHaveBeenCalled()
    expect(await screen.findByTestId('push-preferences-state')).toHaveTextContent('This device now receives notifications.')
    expect(device).toBeChecked()

    await user().click(screen.getByRole('switch', { name: 'Shipment notices' }))
    expect(save).toHaveBeenCalledWith('shop.shipped', false)
    expect(await screen.findByText('Shipment notices: off. You no longer receive it as a notification.')).toBeInTheDocument()

    await user().click(device)
    expect(disable).toHaveBeenCalled()
    expect(device).not.toBeChecked()
  })

  it('says why the device cannot be turned on', async () => {
    renderBlock({ load: async () => settings, enabledHere: async () => false, enable: async () => 'denied' })
    await user().click(await screen.findByRole('switch', { name: 'Notifications on this device' }))
    expect(await screen.findByTestId('push-preferences-error')).toHaveTextContent('Notifications are blocked')
  })

  it('says so when the system has push off or the browser cannot receive it', async () => {
    const { unmount } = renderBlock({ load: async () => ({ ...settings, enabled: false }), enabledHere: async () => false })
    expect(await screen.findByTestId('push-preferences-unavailable')).toHaveTextContent('Notifications are not available here.')
    expect(screen.queryByRole('switch')).toBeNull()
    unmount()
    renderBlock({ load: async () => settings, enabledHere: async () => false, supported: () => false })
    expect(await screen.findByTestId('push-preferences-unavailable')).toHaveTextContent('cannot receive notifications')
  })
})
