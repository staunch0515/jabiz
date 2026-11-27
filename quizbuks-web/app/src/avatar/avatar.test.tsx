import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { BRANDS_DIR, brandIds } from '../../../brands/brand.mjs'
import App from '../App'
import '../messages'
import { keyPaths } from '../qb/messageKeys'
import { PRESETS } from './api'
import { AVATAR_BACKGROUNDS, initialColour } from './colour'
import { AVATAR_MESSAGES } from './messages'
import { photoProblem, squareCrop } from './photo'

/*
 * A taker's avatar (Q114a, docs/quizbuks/plans/impl/Q114.md): the colour of the initial, the presets' drawings in every
 * brand, the four places that show it and the choice on "Edit profile".
 */

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

/** The server for a verified taker with a complete profile, their avatar row (none: the initial) and the choice. */
function server({ avatar = null as Record<string, unknown> | null, failSet = false, failRead = false,
  complete = true, photoEnabled = false, photoMissing = false } = {}) {
  const sets: Record<string, unknown>[] = []
  const uploads: string[] = []
  let row = avatar
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Kai', email: 'kai@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read', 'qb.play'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        // No profile yet: the welcome step.
        return json(200, { items: complete ? [{ id: 'p1', version: 1, attributes: { displayName: 'Kai',
          countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 } }] : [], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:default:QbCountry/query':
        return json(200, { items: [{ id: 'c1', attributes: { code: 'JP', name: { en: 'Japan', zh: '日本', ja: '日本' } } }],
          offset: 0, limit: 300 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:currency':
        return json(200, [{ code: 'JPY', label: 'Japanese yen' }])
      case '/api/queries/qb.app.avatar':
        if (failRead) return json(500, {})
        return json(200, { items: [{ kind: null, preset: null, photo: null, ...row, photoEnabled }], offset: 0,
          limit: 1 })
      case '/api/files':
        uploads.push(new URL(request.url).searchParams.get('policy') ?? '')
        return json(201, { fileId: `f${uploads.length}`, policy: 'qb.taker-avatar' })
      case '/api/processes/QB_AVATAR_SET/latest':
        sets.push(body as Record<string, unknown>)
        if (failSet) {
          return json(422, { status: 422, title: 'Unprocessable', violations: [{ field: 'preset',
            ruleCode: 'QB_AVATAR_PRESET_UNKNOWN', message: 'Choose one of the avatars offered.' }] })
        }
        row = { kind: (body as { kind: string }).kind, preset: (body as { preset?: string }).preset ?? null,
          photo: (body as { photo?: string }).photo ?? null }
        return json(200, { output: row })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      default:
        if (path.startsWith('/api/files/') && path.endsWith('/content/w256')) {
          return photoMissing ? json(404, {}) : new Response('jpeg', { status: 200,
            headers: { 'Content-Type': 'image/jpeg' } })
        }
        return json(404, {})
    }
  })
  return { sets, uploads }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(async () => {
  session.clear()
  session.store('access', 'refresh')
  configureClient({ entry: 'app' })
  await i18next.changeLanguage('en')
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('the initial\'s colour', () => {
  it('is the same for the same id and one of the eight brand colours', () => {
    expect(initialColour('u-123')).toBe(initialColour('u-123'))
    expect(initialColour(null)).toBe(0)
    for (const id of ['a', 'b', 'u1', '019a7f2c-0000-7000-8000-000000000001']) {
      expect(initialColour(id)).toBeGreaterThanOrEqual(0)
      expect(initialColour(id)).toBeLessThan(AVATAR_BACKGROUNDS.length)
    }
  })

  it('spreads takers over every colour', () => {
    const counts = new Array(AVATAR_BACKGROUNDS.length).fill(0) as number[]
    for (let n = 0; n < 800; n++) counts[initialColour(`019a7f2c-${n.toString(16).padStart(4, '0')}-7000-8000`)]++
    for (const count of counts) expect(count).toBeGreaterThan(50)
  })

  it('names colours the theme has, in every brand', () => {
    const theme = readFileSync(join(__dirname, '../qb/theme.css'), 'utf8')
    for (const background of AVATAR_BACKGROUNDS) {
      expect(theme).toContain(`--color-${background.slice('bg-'.length)}:`)
    }
    for (const id of brandIds()) {
      const css = readFileSync(join(BRANDS_DIR, id, 'brand.css'), 'utf8')
      for (let i = 1; i <= AVATAR_BACKGROUNDS.length; i++) {
        expect(css.match(new RegExp(`--qb-avatar-${i}: #[0-9a-f]{6};`, 'g')), `${id} --qb-avatar-${i}`).toHaveLength(2)
      }
    }
  })
})

describe('the presets', () => {
  it('have a drawing in every brand, and no drawing is left over', () => {
    for (const id of brandIds()) {
      const dir = join(BRANDS_DIR, id, 'public', 'avatars')
      expect(readdirSync(dir).sort(), id).toEqual(PRESETS.map((code) => `${code}.svg`).sort())
      for (const code of PRESETS) {
        const svg = readFileSync(join(dir, `${code}.svg`), 'utf8')
        expect(svg, `${id}/${code}`).toMatch(/^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg" viewBox="0 0 64 64"/)
        // Pictures only: nothing that runs, loads or styles anything else.
        expect(svg, `${id}/${code}`).not.toMatch(/<script|<style|<foreignObject|<image|href=|on[a-z]+=/i)
      }
    }
  })

  it('have a name in every language, as their texts have the same keys', () => {
    const english = keyPaths(AVATAR_MESSAGES.en)
    expect(english).toEqual(expect.arrayContaining(PRESETS.map((code) => `avatar.presets.${code}`)))
    expect(keyPaths(AVATAR_MESSAGES.zh)).toEqual(english)
    expect(keyPaths(AVATAR_MESSAGES.ja)).toEqual(english)
  })
})

describe('the avatar (Q114a)', () => {
  it('shows the initial in the taker\'s colour when nothing was chosen', async () => {
    server()
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const avatar = await screen.findByTestId('me-avatar')
    await waitFor(() => expect(avatar).toHaveTextContent('K'))
    expect(avatar).toHaveAttribute('data-avatar', 'initial')
    expect(avatar.className).toContain(AVATAR_BACKGROUNDS[initialColour('u1')])
  })

  it('shows the chosen preset in the head, the drawer and on "Me", and the initial when its drawing fails', async () => {
    server({ avatar: { kind: 'PRESET', preset: 'p09' } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    // (the element is replaced once the avatar is read: look it up each time)
    await waitFor(() => expect(screen.getByTestId('me-avatar')).toHaveAttribute('data-avatar', 'p09'))
    const avatar = screen.getByTestId('me-avatar')
    expect(avatar.querySelector('img')).toHaveAttribute('src', '/avatars/p09.svg')
    expect(avatar.querySelector('img')).toHaveAttribute('alt', '')

    fireEvent.error(avatar.querySelector('img')!)
    await waitFor(() => expect(screen.getByTestId('me-avatar')).toHaveAttribute('data-avatar', 'initial'))
    expect(screen.getByTestId('me-avatar')).toHaveTextContent('K')
  })

  it('shows the chosen preset in a first-level page\'s head and in the drawer it opens', async () => {
    server({ avatar: { kind: 'PRESET', preset: 'p04' } })
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('head-avatar').querySelector('[data-avatar="p04"]')).not.toBeNull())
    await userEvent.click(screen.getByTestId('head-avatar'))
    const drawer = await screen.findByRole('dialog')
    expect(drawer.querySelector('[data-avatar="p04"]')).not.toBeNull()
  })

  it('shows the initial when the avatar cannot be read', async () => {
    server({ failRead: true })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const avatar = await screen.findByTestId('me-avatar')
    await waitFor(() => expect(avatar).toHaveTextContent('K'))
    // The initial, and no error of the avatar's own (the page's other parts are not answered by this server).
    expect(avatar).toHaveAttribute('data-avatar', 'initial')
  })
})

describe('choosing the avatar on "Edit profile" (Q114a)', () => {
  it('offers the initial and the twelve presets, saves a choice at once and shows it', async () => {
    const { sets } = server()
    window.history.replaceState(null, '', '/account/profile')
    const { container } = render(<App />)
    const picker = await screen.findByTestId('avatar-picker')
    await waitFor(() => expect(within(picker).getByTestId('avatar-current')).toHaveTextContent('K'))
    await userEvent.click(within(picker).getByTestId('avatar-change'))
    const dialog = await screen.findByTestId('avatar-dialog')
    expect(within(dialog).getByRole('heading', { name: 'Choose your avatar' })).toBeInTheDocument()
    const choices = within(dialog).getByRole('group', { name: 'Avatars' })
    const buttons = within(choices).getAllByRole('button')
    expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual(['Your initial', 'Owl', 'Fox', 'Cat', 'Rabbit',
      'Penguin', 'Bear', 'Whale', 'Book', 'Mortarboard', 'Light bulb', 'Rocket', 'Globe'])
    expect(within(choices).getByRole('button', { name: 'Your initial' })).toHaveAttribute('aria-pressed', 'true')
    await expectAccessible(container.ownerDocument.body)

    await userEvent.click(within(choices).getByRole('button', { name: 'Mortarboard' }))
    await waitFor(() => expect(sets).toEqual([{ kind: 'PRESET', preset: 'p09' }]))
    await waitFor(() => expect(within(choices).getByRole('button', { name: 'Mortarboard' }))
      .toHaveAttribute('aria-pressed', 'true'))
    expect(within(choices).getByRole('button', { name: 'Your initial' })).toHaveAttribute('aria-pressed', 'false')
    expect(within(picker).getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'p09')
    // The one chosen is not sent again; going back to the initial is.
    await userEvent.click(within(choices).getByRole('button', { name: 'Mortarboard' }))
    await userEvent.click(within(choices).getByRole('button', { name: 'Your initial' }))
    await waitFor(() => expect(sets).toEqual([{ kind: 'PRESET', preset: 'p09' }, { kind: 'INITIAL' }]))
    await waitFor(() => expect(within(picker).getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'initial'))
  })

  it('says why a choice was not saved and keeps the avatar as it was', async () => {
    server({ avatar: { kind: 'PRESET', preset: 'p02' }, failSet: true })
    window.history.replaceState(null, '', '/account/profile')
    render(<App />)
    const picker = await screen.findByTestId('avatar-picker')
    await waitFor(() => expect(within(picker).getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'p02'))
    await userEvent.click(within(picker).getByTestId('avatar-change'))
    const dialog = await screen.findByTestId('avatar-dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rocket' }))
    expect(await within(dialog).findByTestId('avatar-error')).toHaveTextContent('Choose one of the avatars offered.')
    expect(within(dialog).getByRole('button', { name: 'Fox' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(picker).getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'p02')
  })

  it('is not on the welcome step', async () => {
    server({ complete: false })
    window.history.replaceState(null, '', '/welcome')
    render(<App />)
    expect(await screen.findByTestId('profile-name')).toBeInTheDocument()
    expect(screen.queryByTestId('avatar-picker')).toBeNull()
  })
})

describe('a photo as the avatar (Q114b)', () => {
  beforeEach(() => {
    // jsdom has no object URLs or image decoding: the page's own steps stand in.
    URL.createObjectURL = vi.fn(() => 'blob:photo')
    URL.revokeObjectURL = vi.fn()
  })

  it('keeps the middle square, at most 512 pixels, and takes JPEG and PNG only', () => {
    expect(squareCrop(4000, 3000)).toEqual({ sx: 500, sy: 0, side: 3000, size: 512 })
    expect(squareCrop(300, 500)).toEqual({ sx: 0, sy: 100, side: 300, size: 300 })
    expect(photoProblem(new Blob(['x'], { type: 'image/png' }))).toBeNull()
    expect(photoProblem(new Blob(['x'], { type: 'image/gif' }))).toBe('notImage')
    expect(photoProblem({ type: 'image/jpeg', size: 21 * 1024 * 1024 } as Blob)).toBe('tooLarge')
  })

  it('shows the photo, cut to the circle', async () => {
    server({ avatar: { kind: 'PHOTO', photo: 'f9' } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('me-avatar')).toHaveAttribute('data-avatar', 'photo'))
    await waitFor(() => expect(screen.getByTestId('me-avatar').querySelector('img')).toHaveAttribute('src',
      'blob:photo'))
    expect(screen.getByTestId('me-avatar').querySelector('img')).toHaveAttribute('alt', '')
  })

  it('falls back to the initial when the photo is gone', async () => {
    server({ avatar: { kind: 'PHOTO', photo: 'f404' }, photoMissing: true })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('me-avatar').querySelector('[data-avatar="initial"]'))
      .toHaveTextContent('K'))
  })

  it('offers no upload while photos are off, and still removes a photo chosen before', async () => {
    const { sets } = server({ avatar: { kind: 'PHOTO', photo: 'f9' } })
    window.history.replaceState(null, '', '/account/profile')
    render(<App />)
    const picker = await screen.findByTestId('avatar-picker')
    await waitFor(() => expect(within(picker).getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'photo'))
    await userEvent.click(within(picker).getByTestId('avatar-change'))
    const dialog = await screen.findByTestId('avatar-dialog')
    expect(within(dialog).queryByTestId('avatar-photo-upload')).toBeNull()
    await userEvent.click(within(dialog).getByTestId('avatar-photo-remove'))
    const ask = await screen.findByRole('alertdialog', { name: 'Remove your photo?' })
    await userEvent.click(within(ask).getByRole('button', { name: 'Remove photo' }))
    await waitFor(() => expect(sets).toEqual([{ kind: 'INITIAL' }]))
    await waitFor(() => expect(within(picker).getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'initial'))
  })

  it('makes the picture square, shows it, and uploads and saves it with "Use this photo"', async () => {
    const photo = await import('./photo')
    const square = vi.spyOn(photo, 'squarePhoto').mockResolvedValue(new Blob(['square'], { type: 'image/jpeg' }))
    const { sets, uploads } = server({ photoEnabled: true })
    window.history.replaceState(null, '', '/account/profile')
    const { container } = render(<App />)
    const picker = await screen.findByTestId('avatar-picker')
    await userEvent.click(within(picker).getByTestId('avatar-change'))
    const dialog = await screen.findByTestId('avatar-dialog')
    expect(await within(dialog).findByTestId('avatar-photo-upload')).toHaveTextContent('Upload a photo')
    await expectAccessible(container.ownerDocument.body)

    const file = new File(['raw'], 'IMG_0001.png', { type: 'image/png' })
    await userEvent.upload(within(dialog).getByTestId('avatar-photo-input'), file)
    expect(square).toHaveBeenCalledWith(file)
    const preview = await within(dialog).findByTestId('avatar-photo-preview')
    expect(within(preview).getByRole('img', { name: 'Your photo as your avatar' })).toHaveAttribute('src', 'blob:photo')
    expect(sets).toEqual([])
    await userEvent.click(within(preview).getByTestId('avatar-photo-use'))
    await waitFor(() => expect(sets).toEqual([{ kind: 'PHOTO', photo: 'f1' }]))
    expect(uploads).toEqual(['qb.taker-avatar'])
    await waitFor(() => expect(within(picker).getByTestId('avatar-current')).toHaveAttribute('data-avatar', 'photo'))
    expect(within(dialog).getByTestId('avatar-photo-note')).toHaveTextContent('deletes this photo')
  })

  it('says why a picture was not taken, and uploads nothing', async () => {
    const { uploads } = server({ photoEnabled: true })
    window.history.replaceState(null, '', '/account/profile')
    render(<App />)
    await userEvent.click(within(await screen.findByTestId('avatar-picker')).getByTestId('avatar-change'))
    const dialog = await screen.findByTestId('avatar-dialog')
    await userEvent.upload(await within(dialog).findByTestId('avatar-photo-input'),
      new File(['gif'], 'a.gif', { type: 'image/gif' }), { applyAccept: false })
    expect(await within(dialog).findByTestId('avatar-error')).toHaveTextContent('Choose a JPEG or PNG picture.')
    expect(uploads).toEqual([])
  })
})
