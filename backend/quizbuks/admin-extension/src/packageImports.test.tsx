import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { SponsorPackageImports } from './SponsorPackageImports'
import { DATASET, DICTIONARY } from './ids'
import { messages } from './messages'
import { packageImportMessages } from './package-messages'

type Row = Record<string, unknown>
type Body = { offset?: number; limit?: number; filters?: { field: string; op: string; value?: unknown }[];
  sorts?: { field: string; asc: boolean }[] }

/** The server as the Sponsor's import records see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), runProcess: vi.fn() }))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get, POST: server.post },
    unwrap: async (call: Promise<unknown>) => call,
    runProcess: server.runProcess,
    useAuth: () => ({ can: () => true }),
  }
})

const SPONSOR = '0192f0a0-0000-7000-8000-00000000c001'

const ROWS: Row[] = [
  { importId: 'i2', sponsorId: SPONSOR, mode: 'FULL', status: 'RUNNING', formatVersion: 2, stepsDone: 3,
    stepsTotal: 9, lastStep: 'quiz:q1:v:q1v1:version', packageHash: 'a'.repeat(64), startedAt: '2026-11-09T02:00:00Z',
    updatedAt: '2026-11-09T02:05:00Z', finishedAt: null },
  { importId: 'i1', sponsorId: SPONSOR, mode: 'PROFILE', status: 'DONE', formatVersion: 1, stepsDone: 1,
    stepsTotal: 1, lastStep: 'profile', packageHash: 'b'.repeat(64), startedAt: '2026-11-08T02:00:00Z',
    updatedAt: '2026-11-08T02:01:00Z', finishedAt: '2026-11-08T02:01:00Z' },
]

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.post.mockReset().mockImplementation(async (_path: string,
    options: { params: { path: { resourceId: string } }; body: Body }) => {
    expect(options.params.path.resourceId).toBe(DATASET.packageImport)
    return { items: ROWS.filter((row) => (options.body.filters ?? []).every((f) => row[f.field] === f.value))
      .map((attributes) => ({ attributes })) }
  })
  server.get.mockReset().mockImplementation(async (_path: string, options: { params: { path: { urn: string } } }) => {
    if (options.params.path.urn === DICTIONARY.packageImportMode) {
      return [{ code: 'FULL', label: 'Whole package' }, { code: 'PROFILE', label: 'Profile part only' }]
    }
    if (options.params.path.urn === DICTIONARY.packageImportStatus) {
      return [{ code: 'RUNNING', label: 'Not finished' }, { code: 'DONE', label: 'Done' }]
    }
    return []
  })
  server.runProcess.mockReset()
})

describe('SponsorPackageImports', () => {
  it('has its texts in every language', () => {
    const keys = (texts: object): string[] => Object.entries(texts).flatMap(([key, value]) =>
      typeof value === 'object' ? keys(value).map((k) => `${key}.${k}`) : [key]).sort()
    expect(keys(packageImportMessages.zh)).toEqual(keys(packageImportMessages.en))
    expect(keys(packageImportMessages.ja)).toEqual(keys(packageImportMessages.en))
  })

  it('lists the Sponsor’s imports, newest first, read only', async () => {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AppearanceProvider>
          <MemoryRouter><SponsorPackageImports userId={SPONSOR} /></MemoryRouter>
        </AppearanceProvider>
      </QueryClientProvider>,
    )
    const rows = await screen.findAllByTestId('sponsor-package-import')
    expect(rows.map((row) => row.dataset.id)).toEqual(['i2', 'i1'])
    expect(server.post.mock.calls[0][1].body).toMatchObject({ filters: [{ field: 'sponsorId', op: 'eq',
      value: SPONSOR }], sorts: [{ field: 'startedAt', asc: false }] })
    expect(await screen.findByText('Not finished')).toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('3 of 9')
    expect(rows[0]).toHaveTextContent('quiz:q1:v:q1v1:version')
    expect(rows[1]).toHaveTextContent('Version 1')
    expect(screen.queryByRole('button', { name: /abandon|give up|delete/i })).toBeNull()
    expect(server.runProcess).not.toHaveBeenCalled()
    await expectAccessible()
  })
})
