import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { CERTIFICATIONS_PATH } from './api'
import { certificationsMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const KEYBOARD = { certificationId: 'sc1', key: 'sp:sc1', name: { en: 'Keyboard expert', ja: 'キーボード達人' },
  description: { en: 'Knows the switches' }, validityDays: 365, certificationVersion: 1, status: 'ACTIVE',
  createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', rounds: 2, openRounds: 1, holders: 7 }
const OLD = { ...KEYBOARD, certificationId: 'sc0', key: 'sp:sc0', name: { en: 'Old hand' }, status: 'RETIRED',
  rounds: 0, openRounds: 0, holders: 0 }

/** The server for an approved sponsor with the certifications above; `off` refuses creating one (the switch). */
function server({ off = false }: { off?: boolean } = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.publication.write'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.certifications':
        return json(200, { items: [KEYBOARD, OLD], offset: 0, limit: 50 })
      case '/api/processes/QB_SPONSOR_CERTIFICATION_CREATE/latest':
        return off
          ? json(422, { title: 'Unprocessable', detail: 'Sponsors’ own certifications are not offered at the moment.',
            violations: [{ field: 'certificationId', ruleCode: 'QB_SPONSOR_CERTIFICATIONS_OFF',
              message: 'Sponsors’ own certifications are not offered at the moment.' }] })
          : json(200, { output: { ...KEYBOARD, certificationId: 'sc2', key: 'sp:sc2', name: body.name } })
      case '/api/processes/QB_SPONSOR_CERTIFICATION_UPDATE/latest':
        return json(200, { output: { ...KEYBOARD, name: body.name, certificationVersion: body.raiseVersion ? 2 : 1 } })
      case '/api/processes/QB_SPONSOR_CERTIFICATION_RETIRE/latest':
        return json(200, { output: { ...KEYBOARD, status: 'RETIRED' } })
      default:
        return json(404, {})
    }
  })
  const calls = (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { calls }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('the Sponsor’s own certifications (Q88a)', () => {
  it('have every text in every language', () => {
    const english = keyPaths(certificationsMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(certificationsMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
  })

  it('list them by their rows’ names, with rounds and holders; a retired one has no actions', async () => {
    server()
    window.history.replaceState(null, '', CERTIFICATIONS_PATH)
    render(<App />)
    const rows = await screen.findAllByTestId('certification-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByTestId('certification-name')).toHaveTextContent('Keyboard expert')
    expect(rows[0]).toHaveTextContent('365 days')
    expect(rows[0]).toHaveTextContent('1 open of 2')
    expect(within(rows[0]).getByTestId('certification-holders')).toHaveTextContent('7')
    expect(within(rows[0]).getByTestId('certification-status')).toHaveAttribute('data-status', 'ACTIVE')
    expect(within(rows[0]).getByTestId('certification-edit')).toBeInTheDocument()
    expect(within(rows[1]).getByTestId('certification-status')).toHaveAttribute('data-status', 'RETIRED')
    expect(within(rows[1]).queryByTestId('certification-edit')).not.toBeInTheDocument()
    await expectAccessible()
  })

  // F-C1c: a tab of the asset library (no menu item of their own); the old address leads there.
  it('are the asset library’s tab, where their old address leads', async () => {
    server()
    window.history.replaceState(null, '', '/certifications')
    render(<App />)
    await waitFor(() => expect(window.location.pathname + window.location.search).toBe(CERTIFICATIONS_PATH))
    expect(await screen.findAllByTestId('certification-row')).toHaveLength(2)
    expect(screen.getByTestId('assets-tab-certifications')).toHaveAttribute('data-state', 'active')
    expect(screen.getByRole('heading', { level: 2, name: 'Your certifications' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Certifications' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Asset library' })).toHaveAttribute('aria-current', 'page')
  })

  it('define one (a name in at least one language, the validity), and edit one raising its version', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', CERTIFICATIONS_PATH)
    render(<App />)
    await userEvent.click(await screen.findByTestId('certification-add'))
    await userEvent.type(await screen.findByTestId('certification-name-en'), 'Coffee master')
    await userEvent.type(screen.getByTestId('certification-description-en'), 'Knows the beans')
    await userEvent.clear(screen.getByTestId('certification-validity'))
    await userEvent.type(screen.getByTestId('certification-validity'), '180')
    await expectAccessible()
    await userEvent.click(screen.getByTestId('certification-save'))
    await waitFor(() => expect(calls('QB_SPONSOR_CERTIFICATION_CREATE')).toEqual([
      { name: { en: 'Coffee master' }, description: { en: 'Knows the beans' }, validityDays: 180 }]))
    await waitFor(() => expect(screen.queryByTestId('certification-dialog')).not.toBeInTheDocument())

    const rows = await screen.findAllByTestId('certification-row')
    await userEvent.click(within(rows[0]).getByTestId('certification-edit'))
    const dialog = await screen.findByTestId('certification-dialog')
    expect(within(dialog).getByTestId('certification-name-ja')).toHaveValue('キーボード達人')
    await userEvent.click(within(dialog).getByTestId('certification-raise-version'))
    await userEvent.click(within(dialog).getByTestId('certification-save'))
    await waitFor(() => expect(calls('QB_SPONSOR_CERTIFICATION_UPDATE')).toEqual([
      { certificationId: 'sc1', name: { en: 'Keyboard expert', ja: 'キーボード達人' },
        description: { en: 'Knows the switches' }, validityDays: 365, raiseVersion: true }]))
  })

  it('retire one after confirming', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', CERTIFICATIONS_PATH)
    render(<App />)
    const rows = await screen.findAllByTestId('certification-row')
    await userEvent.click(within(rows[0]).getByTestId('certification-retire'))
    const dialog = await screen.findByRole('alertdialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Retire' }))
    await waitFor(() => expect(calls('QB_SPONSOR_CERTIFICATION_RETIRE')).toEqual([{ certificationId: 'sc1' }]))
  })

  it('show the server’s words when the switch is off', async () => {
    server({ off: true })
    window.history.replaceState(null, '', CERTIFICATIONS_PATH)
    render(<App />)
    await userEvent.click(await screen.findByTestId('certification-add'))
    await userEvent.type(await screen.findByTestId('certification-name-en'), 'Coffee master')
    await userEvent.click(screen.getByTestId('certification-save'))
    expect(await screen.findByTestId('certification-error')).toHaveTextContent('not offered')
  })
})
