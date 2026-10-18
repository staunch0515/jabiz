import {
  ApiError,
  configureClient,
  i18n,
  session,
  setMailLanguage,
  type MailLanguage,
  type MailPreference,
} from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider } from '../components/appearance'
import { notify } from '../components/notify'
import { Toaster } from '../components/ui/sonner'
import { expectAccessible } from '../test/axe'
import { MailPreferences, offerMailLanguage } from './index'

const shipped: MailPreference = { template: 'shop.shipped', subscribed: true, displayName: 'Shipment notices' }
const offers: MailPreference = { template: 'shop.offers', subscribed: false, displayName: 'Offers' }
const english: MailLanguage = { effective: 'en', languages: ['zh', 'ja', 'en'], defaultLocale: 'en' }

const user = () => userEvent.setup()

function refused(status: number, ruleCode: string) {
  return new ApiError(status, { status, violations: [{ field: 'template', ruleCode, message: `${ruleCode} message` }] })
}

beforeEach(async () => {
  configureClient({ entry: 'portal' })
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  act(() => notify.dismiss())
  vi.unstubAllGlobals()
})

describe('MailPreferences', () => {
  it('shows a switch per notification by its name, and the language of the mail', async () => {
    const load = vi.fn(async () => [shipped, offers])
    const loadLanguage = vi.fn(async () => english)
    render(<MailPreferences load={load} loadLanguage={loadLanguage} />)

    const items = await screen.findAllByTestId('mail-preference')
    expect(items.map((item) => item.textContent)).toEqual(['Shipment notices', 'Offers'])
    expect(screen.getByRole('switch', { name: 'Shipment notices' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Offers' })).not.toBeChecked()
    expect(screen.getByRole('list', { name: 'Notifications' })).toBeInTheDocument()
    // Not chosen: the application's default, named.
    expect(screen.getByRole('combobox', { name: 'Language of your mail' })).toHaveTextContent('Default (English)')
    expect(screen.queryByTestId('mail-preferences-others')).toBeNull()
    await expectAccessible()
  })

  it('saves a switch at once and says so; a refusal leaves it as it was', async () => {
    const save = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(refused(422, 'INVALID_VALUE'))
    render(<MailPreferences load={async () => [shipped, offers]} loadLanguage={async () => english} save={save} />)
    const switchOf = async (name: string) => screen.findByRole('switch', { name })

    await user().click(await switchOf('Shipment notices'))
    expect(save).toHaveBeenNthCalledWith(1, 'shop.shipped', false)
    expect(await screen.findByTestId('mail-preferences-state')).toHaveTextContent(
      'Shipment notices: off. You no longer receive it by e-mail.')
    expect(await switchOf('Shipment notices')).not.toBeChecked()

    await user().click(await switchOf('Offers'))
    expect(save).toHaveBeenNthCalledWith(2, 'shop.offers', true)
    expect(await screen.findByTestId('mail-preferences-error')).toHaveTextContent('INVALID_VALUE message')
    expect(await switchOf('Offers')).not.toBeChecked()
  })

  it('sets the language of the mail', async () => {
    const saveLanguage = vi.fn(async () => undefined)
    render(<MailPreferences load={async () => [shipped]} loadLanguage={async () => ({ ...english, locale: 'en' })}
      saveLanguage={saveLanguage} />)
    await user().click(await screen.findByRole('combobox', { name: 'Language of your mail' }))
    await user().click(await screen.findByRole('option', { name: '日本語' }))
    expect(saveLanguage).toHaveBeenCalledWith('ja')
    expect(await screen.findByTestId('mail-preferences-state')).toHaveTextContent('Your mail will be in 日本語.')
    expect(screen.getByRole('combobox', { name: 'Language of your mail' })).toHaveTextContent('日本語')
  })

  it('goes back to the default language', async () => {
    const saveLanguage = vi.fn(async () => undefined)
    render(<MailPreferences load={async () => [shipped]} loadLanguage={async () => ({ ...english, locale: 'ja', effective: 'ja' })}
      saveLanguage={saveLanguage} />)
    const select = await screen.findByRole('combobox', { name: 'Language of your mail' })
    expect(select).toHaveTextContent('日本語')
    await user().click(select)
    await user().click(await screen.findByRole('option', { name: 'Default (English)' }))
    expect(saveLanguage).toHaveBeenCalledWith(null)
    expect(await screen.findByTestId('mail-preferences-state')).toHaveTextContent(
      'Your mail will be in the default language, English.')
    expect(screen.getByRole('combobox', { name: 'Language of your mail' })).toHaveTextContent('Default (English)')
  })

  it('shows a chosen language the interface does not offer as the default, never an empty choice', async () => {
    render(<MailPreferences load={async () => [shipped]}
      loadLanguage={async () => ({ locale: 'ko', effective: 'en', languages: ['ja', 'en', 'ko'], defaultLocale: 'en' })} />)
    expect(await screen.findByRole('combobox', { name: 'Language of your mail' })).toHaveTextContent('Default (English)')
  })

  it('lists the notifications of other entries the user turned off, to turn them on again', async () => {
    const save = vi.fn(async () => undefined)
    const other: MailPreference = { template: 'app.offers', subscribed: false, displayName: 'Offers in the app',
      otherEntry: true }
    render(<MailPreferences load={async () => [shipped, other]} loadLanguage={async () => english} save={save} />)
    const others = await screen.findByTestId('mail-preferences-others')
    expect(others).toHaveTextContent('Other e-mail you turned off')
    expect(within(screen.getByRole('list', { name: 'Notifications' })).getAllByRole('switch')).toHaveLength(1)
    const offers = within(others).getByRole('switch', { name: 'Offers in the app' })
    expect(offers).not.toBeChecked()
    await user().click(offers)
    expect(save).toHaveBeenCalledWith('app.offers', true)
    expect(await screen.findByRole('switch', { name: 'Offers in the app' })).toBeChecked()
    await expectAccessible()
  })

  it('shows at once a mail language set elsewhere on the page', async () => {
    session.store('access', 'refresh')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ userId: 'u' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } })))
    const loadLanguage = vi.fn().mockResolvedValueOnce(english)
      .mockResolvedValue({ ...english, locale: 'ja', effective: 'ja' })
    render(<MailPreferences load={async () => [shipped]} loadLanguage={loadLanguage} />)
    const select = await screen.findByRole('combobox', { name: 'Language of your mail' })
    expect(select).toHaveTextContent('Default (English)')
    await act(() => setMailLanguage('ja'))
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Language of your mail' })).toHaveTextContent('日本語'))
  })

  it('says when there is nothing to choose, and offers no choice of one language', async () => {
    render(<MailPreferences load={async () => []}
      loadLanguage={async () => ({ effective: 'en', languages: ['en'], defaultLocale: 'en' })} />)
    expect(await screen.findByTestId('mail-preferences-none')).toHaveTextContent(
      'There are no notifications to choose here.')
    expect(screen.queryByRole('combobox')).toBeNull()
    await expectAccessible()
  })

  it('reads the names again in the new interface language', async () => {
    const load = vi.fn(async () => [shipped])
    render(<MailPreferences load={load} loadLanguage={async () => english} />)
    await screen.findAllByTestId('mail-preference')
    await act(() => i18n.changeLanguage('ja'))
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('list', { name: 'お知らせ' })).toBeInTheDocument()
  })

  it('shows a failure to load, and loads again on request', async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue([shipped])
    render(<MailPreferences load={load} loadLanguage={async () => english} />)
    expect(await screen.findByTestId('mail-preferences-error')).toHaveTextContent(
      'Your mail settings could not be loaded or saved.')
    await user().click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('switch', { name: 'Shipment notices' })).toBeChecked()
    expect(screen.queryByTestId('mail-preferences-error')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  })
})

describe('offerMailLanguage', () => {
  function toaster() {
    render(
      <AppearanceProvider>
        <Toaster />
      </AppearanceProvider>,
    )
  }

  /** Answers the mail language as `effective`. */
  function server(effective: string) {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ locale: effective, effective,
      languages: ['zh', 'ja', 'en'] }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
  }

  it('offers the new interface language for the mail, which changes only once accepted', async () => {
    session.store('access', 'refresh')
    server('en')
    toaster()
    const set = vi.fn(async () => undefined)
    await act(() => offerMailLanguage('ja', set))
    const offer = await screen.findByText('メールも日本語で受け取りますか？')
    expect(set).not.toHaveBeenCalled()
    const toast = offer.closest('li')!
    // Says where to change it later, and stays until answered.
    expect(toast).toHaveTextContent('アカウント設定の「メール」')
    expect(within(toast as HTMLElement).getByRole('button', { name: '今はしない' })).toBeInTheDocument()
    await user().click(within(toast as HTMLElement).getByRole('button', { name: 'メールにも使う' }))
    expect(set).toHaveBeenCalledWith('ja')
    expect(await screen.findByText('今後のメールは日本語でお送りします。')).toBeInTheDocument()
    await expectAccessible()
  })

  it('offers nothing when the mail is in that language already, or without a session', async () => {
    server('ja')
    toaster()
    const set = vi.fn(async () => undefined)
    session.store('access', 'refresh')
    await act(() => offerMailLanguage('ja', set))
    session.clear()
    await act(() => offerMailLanguage('zh', set))
    expect(screen.queryByRole('button', { name: /メールにも使う|用于邮件/ })).toBeNull()
    expect(set).not.toHaveBeenCalled()
  })
})
