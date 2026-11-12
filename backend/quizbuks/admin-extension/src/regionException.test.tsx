import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { PERMISSION, REGION_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import { RegionExceptionAction } from './RegionExceptionAction'

type Row = Record<string, unknown>

/** The server as the action sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  inForce: [] as Record<string, unknown>[],
  runQuery: vi.fn(),
  runProcess: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    useAuth: () => ({ entry: 'admin', can: (p: string) => server.permissions.has(p) }),
  }
})

const SPONSOR = 'sponsor-1'
const PUBLICATION = '0192f0a0-0000-7000-8000-00000000e001'

const exception = (change: Row): Row => ({ exceptionId: 'x1', kind: 'SPONSOR', publicationId: null, publicationName: null,
  foreignVideo: null, sponsorId: SPONSOR, ownerId: SPONSOR, company: 'Acme', operatorId: null, operatorName: null,
  reason: 'Mirrored', expiresOn: null, revoked: false, revokeReason: null, inForce: true, createdAt: null, createdBy: null,
  changedAt: null, changedBy: null, ...change })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.regionRead, PERMISSION.regionWrite])
  server.inForce = []
  server.runQuery.mockReset().mockImplementation(async () => ({ items: server.inForce, offset: 0, limit: 200 }))
  server.runProcess.mockReset().mockResolvedValue({ exceptionId: 'new' })
})

function renderAction(kind: 'SPONSOR' | 'PUBLICATION', id: string, name: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter>
          <RegionExceptionAction kind={kind} id={id} name={name} />
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the mainland China exception from where a Sponsor or a publication is seen (F-F11b)', () => {
  it('shows nothing to who may not write exceptions, and does not even read them', async () => {
    server.permissions = new Set([PERMISSION.regionRead])
    renderAction('SPONSOR', SPONSOR, 'Acme')
    await Promise.resolve()
    expect(screen.queryByTestId('region-add-for-sponsor')).not.toBeInTheDocument()
    expect(screen.queryByTestId('region-in-force')).not.toBeInTheDocument()
    expect(server.runQuery).not.toHaveBeenCalled()
  })

  it('sets a Sponsor\'s exception: the target is filled in, the reason is left to the person and required', async () => {
    const { container } = renderAction('SPONSOR', SPONSOR, 'Acme')
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('region-add-for-sponsor'))
    const dialog = await screen.findByTestId('region-exception-dialog')
    // Nothing is suggested as the reason; saving waits for one.
    expect(within(dialog).getByTestId('region-exception-reason')).toHaveValue('')
    expect(within(dialog).getByTestId('region-exception-save')).toBeDisabled()
    await expectAccessible(container.ownerDocument.body)
    await user.type(within(dialog).getByTestId('region-exception-reason'), 'Test in mainland China')
    await user.type(within(dialog).getByTestId('region-exception-until'), '2026-12-31')
    await user.click(within(dialog).getByTestId('region-exception-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(REGION_PROCESS.add,
      { kind: 'SPONSOR', sponsorId: SPONSOR, reason: 'Test in mainland China', expiresOn: '2026-12-31' }))
    await waitFor(() => expect(screen.queryByTestId('region-exception-dialog')).not.toBeInTheDocument())
  })

  it('sets a publication\'s exception without a last day, and shows the server\'s refusal', async () => {
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { detail: 'Not your Sponsor.' }))
    renderAction('PUBLICATION', PUBLICATION, 'Capitals week')
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('region-add-for-publication'))
    const dialog = await screen.findByTestId('region-exception-dialog')
    await user.type(within(dialog).getByTestId('region-exception-reason'), 'Why')
    await user.click(within(dialog).getByTestId('region-exception-save'))
    expect(await within(dialog).findByTestId('region-exception-problem')).toHaveTextContent('Not your Sponsor.')
    expect(server.runProcess).toHaveBeenCalledWith(REGION_PROCESS.add,
      { kind: 'PUBLICATION', publicationId: PUBLICATION, reason: 'Why' })
    // The dialog stays open for another try.
    expect(screen.getByTestId('region-exception-dialog')).toBeInTheDocument()
    expect(TEMPLATE.regionExceptions).toBeTruthy()
  })

  it('says an exception is in force, with its last day, instead of offering another', async () => {
    server.inForce = [exception({ expiresOn: '2026-12-31' }),
      exception({ exceptionId: 'x2', kind: 'PUBLICATION', publicationId: PUBLICATION, sponsorId: null, expiresOn: null })]
    const first = renderAction('SPONSOR', SPONSOR, 'Acme')
    expect(await screen.findByTestId('region-in-force')).toHaveTextContent('until 2026-12-31')
    expect(screen.queryByTestId('region-add-for-sponsor')).not.toBeInTheDocument()
    expect(screen.getByTestId('region-in-force')).toHaveAttribute('href', '/qb/region')
    first.unmount()
    renderAction('PUBLICATION', PUBLICATION, 'Capitals week')
    expect(await screen.findByTestId('region-in-force')).toHaveTextContent('until revoked')
    // A Sponsor's exception does not cover another's.
  })

  it('offers a Sponsor without an exception the button although another has one', async () => {
    server.inForce = [exception({})]
    renderAction('SPONSOR', 'someone-else', 'Beta')
    expect(await screen.findByTestId('region-add-for-sponsor')).toBeInTheDocument()
    expect(screen.queryByTestId('region-in-force')).not.toBeInTheDocument()
  })
})
