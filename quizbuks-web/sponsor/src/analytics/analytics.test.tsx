import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const P1 = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b'
const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const DASHBOARD = { live: 2, paused: 1, fundsShort: 0, answering: 3, spentToday: '1200', available: '48800',
  pendingCount: 1, pendingAmount: '600', budgetRemaining: '9000', dayStart: '2026-10-17T15:00:00Z',
  zone: 'Asia/Tokyo', refreshedAt: '2026-10-18T01:10:00Z' }

/** Seven days of the deployment's zone (Tokyo), each starting at 15:00 UTC. */
const WEEK = Array.from({ length: 7 }, (_, i) => ({
  bucketStart: new Date(Date.UTC(2026, 9, 11 + i, 15)).toISOString(),
  day: `2026-10-${String(12 + i).padStart(2, '0')}`,
  views: 4, starts: 3, completions: i, passes: 1, winners: 1, spent: String(i * 100), gap: i === 2, zone: 'Asia/Tokyo',
}))
/** The last 24 hours from 02:00 UTC (11:00 in Tokyo): Tokyo's midnight is the 14th bucket. */
const HOURS = Array.from({ length: 24 }, (_, i) => ({
  bucketStart: new Date(Date.UTC(2026, 9, 17, 2 + i)).toISOString(),
  day: i < 13 ? '2026-10-17' : '2026-10-18', views: 0, starts: 0, completions: i === 23 ? 2 : 0, passes: 0, winners: 0,
  spent: '0', gap: false, zone: 'Asia/Tokyo',
}))
/** The server's totals of those buckets (the page adds nothing up). */
const WEEK_TOTALS = { views: 28, starts: 21, completions: 21, passes: 7, winners: 7, spent: '2100', zone: 'Asia/Tokyo' }
const HOURS_TOTALS = { views: 0, starts: 0, completions: 2, passes: 0, winners: 0, spent: '0', zone: 'Asia/Tokyo' }
const TOP = [{ publicationId: P1, name: 'Big prize', status: 'LIVE', completions7d: 21, spent7d: '2100',
  completionsTotal: 40 }]

/** A publication with fewer completions than the minimum group: counts shown, the rest held back. */
const OVERVIEW_SMALL = { publicationId: P1, name: 'Big prize', status: 'LIVE', views: 10, starts: 8, completions: 4,
  passes: 3, winners: 3, spent: '300', inProgress: 4, completionRate: '0.5', funnelViews: 10,
  viewsBelowStarts: false, viewToStart: '0.8', startToComplete: '0.5', completeToPass: '0.75',
  largestDrop: 'START_COMPLETE', basedOn: 4, avgDurationSec: null, timeouts: null, suppressed: true, minGroup: 5,
  last1h: 1, last24h: 4, last7d: 4, last30d: 4, appCountedAt: '2026-10-18T01:10:00Z', shownAt: null,
  refreshedAt: '2026-10-18T01:10:00Z', zone: 'Asia/Tokyo' }
const OVERVIEW_BIG = { ...OVERVIEW_SMALL, completions: 12, basedOn: 10, avgDurationSec: '42.5', timeouts: 2,
  suppressed: false, largestDrop: 'VIEW_START', viewsBelowStarts: true }

const QUESTIONS_SMALL = [1, 2].map((no) => ({ questionNo: no, stem: `Question ${no}?`, points: no + 1,
  completions: null, correct: null, correctRate: null, difficulty: null, suppressed: true, basedOn: 4, minGroup: 5,
  shownAt: null }))
const QUESTIONS_BIG = [
  { questionNo: 1, stem: 'The capital of France?', points: 2, completions: 10, correct: 9, correctRate: '0.9',
    difficulty: 'EASY', suppressed: false, basedOn: 10, minGroup: 5, shownAt: '2026-10-18T01:10:00Z' },
  { questionNo: 2, stem: 'The square root of 2?', points: 3, completions: 10, correct: 3, correctRate: '0.3',
    difficulty: 'HARD', suppressed: false, basedOn: 10, minGroup: 5, shownAt: '2026-10-18T01:10:00Z' },
]

const VERSION_2 = { versionId: 'v2', versionNumber: 2, label: 'v2.0', title: 'Capitals', current: true,
  replacedAt: null }
const VERSION_1 = { versionId: 'v1', versionNumber: 1, label: 'v1.0', title: 'Capitals', current: false,
  replacedAt: '2026-10-16T03:00:00Z' }

interface Server {
  dashboard?: { status: number; body: unknown }
  top?: unknown[]
  week?: unknown[]
  weekTotals?: Record<string, unknown>
  overview?: unknown[]
  overviewError?: boolean
  questions?: unknown[]
  /** The versions the publication ran on (Q51b); one (the current) when not given. */
  versions?: unknown[]
  export?: { status: number; body: unknown }
  /** Q73e-1: the figures by answer language. */
  languages?: unknown[]
}

/** The server as the console sees it, for a signed-in, approved sponsor. Records what the pages asked for. */
function server(options: Server = {}) {
  const asked: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname) + (url.pathname.endsWith('/export') ? url.search : '')
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') asked.push({ path, body })
    const page = (items: unknown[]) => json(200, { items, offset: 0, limit: items.length })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.stats.read'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.sponsor.dashboard':
        if (options.dashboard) return json(options.dashboard.status, options.dashboard.body)
        return page([DASHBOARD])
      case '/api/queries/qb.sponsor.analytics.top':
        return page(options.top ?? TOP)
      case '/api/queries/qb.sponsor.analytics.trend': {
        const params = (body?.params ?? {}) as Record<string, string>
        return page(params.bucket === 'HOUR' ? HOURS : (options.week ?? WEEK))
      }
      case '/api/queries/qb.sponsor.analytics.trend-totals': {
        const params = (body?.params ?? {}) as Record<string, string>
        return page([params.bucket === 'HOUR' ? HOURS_TOTALS : (options.weekTotals ?? WEEK_TOTALS)])
      }
      case '/api/queries/qb.sponsor.analytics.overview':
        if (options.overviewError) return json(500, { title: 'Internal error' })
        return page(options.overview ?? [OVERVIEW_SMALL])
      case '/api/queries/qb.sponsor.analytics.questions':
        return page(options.questions ?? QUESTIONS_SMALL)
      case '/api/queries/qb.sponsor.analytics.versions':
        return page(options.versions ?? [VERSION_2])
      case '/api/queries/qb.sponsor.analytics.languages':
        return page(options.languages ?? [])
      default:
        if (path.includes('/export?format=')) {
          if (options.export) return json(options.export.status, options.export.body)
          return new Response('bucketStart\r\n', { status: 200, headers: { 'Content-Type': 'text/csv',
            'Content-Disposition': 'attachment; filename="trend.csv"' } })
        }
        return json(404, {})
    }
  })
  const of = (path: string) => asked.filter((a) => a.path === path).map((a) => a.body)
  return {
    trend: () => of('/api/queries/qb.sponsor.analytics.trend'),
    totals: () => of('/api/queries/qb.sponsor.analytics.trend-totals'),
    questions: () => of('/api/queries/qb.sponsor.analytics.questions'),
    dashboard: () => of('/api/queries/qb.sponsor.dashboard'),
    top: () => of('/api/queries/qb.sponsor.analytics.top'),
    exports: () => asked.filter((a) => a.path.includes('/export')),
  }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('the dashboard', () => {
  it('counts the hour that began this minute in the top publications', async () => {
    // A bucket starting at the minute "now" was rounded down to would be left out: the time asked for is not before now.
    const before = Date.now()
    const { top } = server()
    window.history.replaceState(null, '', '/dashboard')
    render(<App />)
    await screen.findByTestId('top-row')
    const at = Date.parse((top()[0] as { params: { at: string } }).params.at)
    expect(at).toBeGreaterThanOrEqual(before)
    expect(at - before).toBeLessThan(120_000)
  })

  it('shows the key figures, the week by the server\'s days and the top publications', async () => {
    const { trend, totals, dashboard, top } = server()
    window.history.replaceState(null, '', '/dashboard')
    const user = userEvent.setup()
    render(<App />)
    expect(await screen.findByTestId('metric-answering-value')).toHaveTextContent('3')
    // Each figure carries a decorative picture (Q32).
    expect(screen.getByTestId('metric-answering').querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByTestId('metric-live')).toHaveTextContent('2 Running')
    expect(screen.getByTestId('metric-paused')).toHaveTextContent('1 Paused')
    expect(screen.getByTestId('metric-funds-short')).toHaveTextContent('0 Wallet too low')
    expect(screen.getByTestId('metric-spent-today-value')).toHaveTextContent('1,200 Kudos')
    expect(screen.getByTestId('metric-available-value')).toHaveTextContent('48,800 Kudos')
    expect(screen.getByTestId('metric-pending-value')).toHaveTextContent('600 Kudos (1)')
    expect(screen.getByTestId('metric-budget-value')).toHaveTextContent('9,000 Kudos')
    // Today and the last update in Tokyo time: 15:00 UTC is midnight, 01:10 UTC is 10:10.
    expect(screen.getByTestId('metric-spent-today')).toHaveTextContent('00:00 (Asia/Tokyo)')
    expect(screen.getByTestId('analytics-updated')).toHaveTextContent('Updated every 10 minutes')
    expect(screen.getByTestId('analytics-updated')).toHaveTextContent('10:10 (Asia/Tokyo)')
    expect(document.body).not.toHaveTextContent('JPY')

    // The charts are named and summed up; their data is in a table behind a disclosure.
    expect(await screen.findByTestId('week-completions-caption')).toHaveTextContent('Completions by day: 21 in all')
    expect(screen.getByTestId('week-spent-caption')).toHaveTextContent('2,100 Kudos in all')
    expect(screen.getByRole('img', { name: 'Completions by day: 21 in all' })).toBeInTheDocument()
    const table = screen.getByTestId('week-table')
    expect(table).not.toHaveAttribute('open')
    await user.click(within(table).getByText('Show the data as a table'))
    expect(table).toHaveAttribute('open')
    const rows = within(table).getAllByTestId('week-row')
    expect(rows).toHaveLength(7)
    expect(rows[0]).toHaveTextContent('10/12')
    expect(rows[6]).toHaveTextContent('10/18')
    expect(rows[2]).toHaveTextContent('Includes a pause')
    expect(screen.getByTestId('week-gap-note')).toBeInTheDocument()
    // The totals row and the captions are the server's totals, not added up on the page.
    expect(within(table).getByTestId('week-totals')).toHaveTextContent('2,100 Kudos')
    // The chart and the top table say which seven days they mean.
    expect(screen.getByTestId('dashboard-trend')).toHaveTextContent('Today and the 6 days before it')
    expect(screen.getByTestId('dashboard-top')).toHaveTextContent('last 7 × 24 hours')

    // The week by the day, of every publication; its totals with the same parameters; the top five; "now" given.
    expect(trend()[0]).toMatchObject({ params: { bucket: 'DAY' } })
    expect(totals()[0]).toEqual({ params: trend()[0]?.params, limit: 1 })
    expect(trend()[0]?.params).not.toHaveProperty('publicationId')
    expect(dashboard()[0]).toMatchObject({ params: { at: expect.any(String) }, limit: 1 })
    expect(top()[0]).toMatchObject({ limit: 5, sorts: [{ field: 'completions7d', asc: false }] })
    const topRow = await screen.findByTestId('top-row')
    expect(within(topRow).getByTestId('top-completions')).toHaveTextContent('21')
    await user.click(within(topRow).getByRole('link', { name: 'Analytics of Big prize' }))
    await waitFor(() => expect(window.location.pathname).toBe(`/publications/${P1}/analytics`))
    expect(await screen.findByTestId('publication-analytics')).toBeInTheDocument()
  })

  it('says when nothing was counted yet and nothing happened', async () => {
    server({ dashboard: { status: 200, body: { items: [{ ...DASHBOARD, refreshedAt: null, zone: null }] } },
      top: [], week: WEEK.map((w) => ({ ...w, completions: 0, views: 0, starts: 0, spent: '0', gap: false, zone: null })),
      weekTotals: { views: 0, starts: 0, completions: 0, passes: 0, winners: 0, spent: '0', zone: null } })
    window.history.replaceState(null, '', '/dashboard')
    render(<App />)
    expect(await screen.findByTestId('analytics-updated')).toHaveTextContent('have not been counted yet')
    expect(await screen.findByTestId('top-empty')).toHaveTextContent('No publication had completions')
    expect(await screen.findByTestId('week')).toHaveTextContent('Nothing happened in this period.')
    expect(screen.getByTestId('week')).toHaveTextContent('Hours and days in UTC.')
  })

  it('offers to try again when the figures cannot be read', async () => {
    server({ dashboard: { status: 500, body: { title: 'Internal error' } } })
    window.history.replaceState(null, '', '/dashboard')
    render(<App />)
    expect(await screen.findByText('The figures could not be loaded.')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Try again' }).length).toBeGreaterThan(0)
  })
})

describe('a publication\'s analytics', () => {
  it('shows the counts, the funnel\'s largest drop, and holds back below the minimum group', async () => {
    server()
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    render(<App />)
    expect(await screen.findByTestId('analytics-title')).toHaveTextContent('Analytics: Big prize')
    expect(screen.getByTestId('overview-views-value')).toHaveTextContent('10')
    expect(screen.getByTestId('overview-completions-value')).toHaveTextContent('4')
    expect(screen.getByTestId('overview-completion-rate-value')).toHaveTextContent('50%')
    expect(screen.getByTestId('overview-spent-value')).toHaveTextContent('300 Kudos')
    // The figures are the job's snapshot: as of its last run, in Tokyo time.
    expect(screen.getByTestId('overview-as-of')).toHaveTextContent('As of')
    expect(screen.getByTestId('overview-as-of')).toHaveTextContent('10:10 (Asia/Tokyo)')
    expect(screen.getByTestId('overview-in-progress')).toHaveTextContent('In progress')
    expect(screen.getByTestId('overview-in-progress')).toHaveTextContent('10:10 (Asia/Tokyo)')
    expect(screen.getByTestId('app-counted')).toHaveTextContent('Counted at')
    expect(screen.getByTestId('app-counted')).toHaveTextContent('10:10 (Asia/Tokyo)')
    expect(screen.getByTestId('analytics-updated')).toHaveTextContent('10:10 (Asia/Tokyo)')
    // Below k: the average time and the timeouts are hidden, and the page says why.
    expect(screen.getByTestId('overview-avg-duration-value')).toHaveTextContent('Hidden')
    expect(screen.getByTestId('overview-timeouts-value')).toHaveTextContent('Hidden')
    expect(screen.getByTestId('overview-k')).toHaveTextContent('Shown once at least 5 people have completed it (4 so far).')

    // The largest drop: a badge on its stage, and a sentence.
    const completions = screen.getByTestId('funnel-completions')
    expect(completions).toHaveAttribute('data-largest-drop', 'true')
    expect(within(completions).getByTestId('funnel-largest-drop')).toHaveTextContent('Largest drop')
    expect(screen.getByTestId('funnel-starts')).not.toHaveAttribute('data-largest-drop')
    expect(screen.getByTestId('funnel-summary'))
      .toHaveTextContent('The largest drop is from "Started" to "Completed": 50% do not go on.')
    expect(within(screen.getByTestId('funnel-table')).getByText('Completed (Largest drop)')).toBeInTheDocument()

    // Every question held back, said once for all and on each row.
    expect(await screen.findByTestId('questions-suppressed'))
      .toHaveTextContent('shown once at least 5 people have answered (4 so far)')
    const rows = screen.getAllByTestId('question-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByTestId('question-suppressed'))
      .toHaveTextContent('Shown once at least 5 people have answered.')
    expect(within(rows[0]).queryByTestId('question-rate')).toBeNull()
    expect(rows[0]).toHaveTextContent('Question 1?')

    // The App's own windows beside the trend.
    expect(screen.getByTestId('app-last24h')).toHaveTextContent('4')
  })

  it('shows a publication of several languages by answer language, its results over the minimum group (Q73e-1)',
    async () => {
      server({ overview: [OVERVIEW_BIG], questions: QUESTIONS_BIG, languages: [
        { language: 'zh', starts: 12, completions: 10, passes: 8, completionRate: '0.8333', passRate: '0.8',
          averageScore: '4.2', suppressed: false, minGroup: 5 },
        { language: 'en', starts: 3, completions: 2, passes: null, completionRate: null, passRate: null,
          averageScore: null, suppressed: true, minGroup: 5 }] })
      window.history.replaceState(null, '', `/publications/${P1}/analytics`)
      const { container } = render(<App />)
      const section = await screen.findByTestId('analytics-languages')
      const rows = within(section).getAllByTestId('analytics-language')
      expect(rows.map((r) => r.dataset.language)).toEqual(['zh', 'en'])
      expect(rows[0]).toHaveTextContent('80%')
      expect(rows[0]).toHaveTextContent('4.2')
      expect(rows[1]).toHaveTextContent('Fewer than 5')
      await expectAccessible(container)
    })

  it('shows no language section for a publication of one language (Q73e-1)', async () => {
    server({ overview: [OVERVIEW_BIG], questions: QUESTIONS_BIG, languages: [{ language: 'en', starts: 3,
      completions: 2, suppressed: true, minGroup: 5 }] })
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    render(<App />)
    await screen.findAllByTestId('question-row')
    expect(screen.queryByTestId('analytics-languages')).toBeNull()
  })

  it('shows each question\'s right rate and difficulty over the minimum group', async () => {
    server({ overview: [OVERVIEW_BIG], questions: QUESTIONS_BIG })
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    render(<App />)
    const rows = await screen.findAllByTestId('question-row')
    expect(within(rows[0]).getByTestId('question-rate')).toHaveTextContent('90%')
    expect(within(rows[0]).getByTestId('question-difficulty')).toHaveTextContent('Easy')
    expect(within(rows[1]).getByTestId('question-difficulty')).toHaveTextContent('Hard')
    expect(screen.queryByTestId('questions-suppressed')).toBeNull()
    expect(screen.getByTestId('questions-based-on')).toHaveTextContent('Based on 10 completions')
    expect(screen.getByTestId('overview-avg-duration-value')).toHaveTextContent('42.5 s')
    expect(screen.getByTestId('overview-timeouts-value')).toHaveTextContent('2')
    expect(screen.getByTestId('overview-k')).toHaveTextContent('Based on 10 completions.')
    expect(screen.getByTestId('funnel-starts')).toHaveAttribute('data-largest-drop', 'true')
    expect(screen.getByTestId('funnel-views-below')).toBeInTheDocument()
  })

  it('shows the questions of the version chosen when the publication switched its version (Q51b)', async () => {
    const { questions, exports } = server({ overview: [OVERVIEW_BIG], questions: QUESTIONS_BIG,
      versions: [VERSION_2, VERSION_1] })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:export')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    const user = userEvent.setup()
    render(<App />)
    const picker = await screen.findByTestId('questions-versions')
    expect(within(picker).getByTestId('questions-version')).toHaveTextContent('v2.0 (current)')
    expect(picker).toHaveTextContent('each version\'s answers are counted apart')
    expect(questions().at(-1)).toMatchObject({ params: { publicationId: P1 } })
    await expectAccessible()
    await user.click(within(picker).getByTestId('questions-version'))
    await user.click(await screen.findByRole('option', { name: /v1\.0 \(until/ }))
    await waitFor(() => expect(questions().at(-1)).toMatchObject({ params: { publicationId: P1, versionId: 'v1' } }))
    expect(screen.getByTestId('questions-version')).toHaveTextContent('v1.0 (until')
    await user.click(screen.getByTestId('export-questions-csv'))
    await waitFor(() => expect(exports().at(-1)).toEqual({
      path: '/api/queries/qb.sponsor.analytics.questions/export?format=csv',
      body: { params: { publicationId: P1, versionId: 'v1' }, sorts: [{ field: 'questionNo', asc: true }] } }))
  })

  it('offers no choice of version when the publication ran on one', async () => {
    server({ overview: [OVERVIEW_BIG], questions: QUESTIONS_BIG })
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    render(<App />)
    await screen.findAllByTestId('question-row')
    expect(screen.queryByTestId('questions-versions')).toBeNull()
  })

  it('switches between the last 24 hours and the last 7 days, with their tables', async () => {
    const { trend } = server()
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    const user = userEvent.setup()
    render(<App />)
    const chart = await screen.findByTestId('trend')
    expect(chart).toHaveAttribute('data-range', 'HOURS_24')
    expect(chart).toHaveAttribute('data-buckets', '24')
    expect(trend().at(-1)).toMatchObject({ params: { bucket: 'HOUR', publicationId: P1 } })
    expect(screen.getByTestId('trend-completions-caption')).toHaveTextContent('Completions by hour: 2 in all')
    // The hours are Tokyo's: the first bucket (02:00 UTC) is 11:00; the rows that start a day carry its date.
    await user.click(within(screen.getByTestId('trend-table')).getByText('Show the data as a table'))
    const hours = within(screen.getByTestId('trend-table')).getAllByTestId('trend-row')
    expect(hours[0]).toHaveTextContent('10/17 11:00')
    expect(hours[1].querySelector('th')).toHaveTextContent(/^12:00$/)
    expect(hours[13]).toHaveTextContent('10/18 00:00')
    expect(within(screen.getByTestId('trend-table')).getByTestId('trend-totals')).toHaveTextContent('2')

    await user.click(screen.getByRole('radio', { name: 'Last 7 days (by the day)' }))
    await waitFor(() => expect(screen.getByTestId('trend')).toHaveAttribute('data-range', 'DAYS_7'))
    expect(screen.getByTestId('trend')).toHaveAttribute('data-buckets', '7')
    expect(trend().at(-1)).toMatchObject({ params: { bucket: 'DAY', publicationId: P1 } })
  })

  it('exports the trend shown, the overview and the questions as CSV or Excel', async () => {
    const { exports, trend } = server()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:export')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    const user = userEvent.setup()
    render(<App />)
    await screen.findByTestId('trend')
    await waitFor(() => expect(screen.getByTestId('export-trend-csv')).toBeEnabled())
    await user.click(screen.getByTestId('export-trend-csv'))
    await waitFor(() => expect(exports()).toHaveLength(1))
    // The same period as the chart's.
    expect(exports()[0]).toEqual({ path: '/api/queries/qb.sponsor.analytics.trend/export?format=csv',
      body: { params: trend().at(-1)?.params, sorts: [{ field: 'bucketStart', asc: true }] } })
    await waitFor(() => expect(clicked).toHaveBeenCalled())
    expect(clicked.mock.instances[0]).toHaveProperty('download', 'trend.csv')
    await user.click(screen.getByTestId('export-questions-xlsx'))
    await waitFor(() => expect(exports()).toHaveLength(2))
    expect(exports()[1]).toEqual({ path: '/api/queries/qb.sponsor.analytics.questions/export?format=xlsx',
      body: { params: { publicationId: P1 }, sorts: [{ field: 'questionNo', asc: true }] } })
    await user.click(screen.getByTestId('export-overview-csv'))
    await waitFor(() => expect(exports()).toHaveLength(3))
    expect(exports()[2]).toMatchObject({ path: '/api/queries/qb.sponsor.analytics.overview/export?format=csv',
      body: { params: { publicationId: P1 } } })
    expect(screen.queryByText(/PDF/)).toBeNull()
  })

  it('says why an export failed', async () => {
    server({ export: { status: 422, body: { code: 'REPORT_TOO_LARGE', detail: 'Too many rows.' } } })
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    const user = userEvent.setup()
    render(<App />)
    await screen.findByTestId('analytics-overview')
    await user.click(screen.getByTestId('export-overview-csv'))
    expect(await screen.findByTestId('export-error')).toHaveTextContent('Too many rows.')
  })

  it('does not exist for another sponsor\'s publication, and asks for nothing else of it', async () => {
    const { trend, totals, questions } = server({ overview: [] })
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    render(<App />)
    expect(await screen.findByText('This publication does not exist, or it is not yours.')).toBeInTheDocument()
    expect(screen.queryByTestId('analytics-overview')).toBeNull()
    expect(screen.queryByTestId('analytics-export')).toBeNull()
    // Neither the questions nor the trend of a publication that is not the sponsor's are ever asked for.
    expect(questions()).toEqual([])
    expect(trend()).toEqual([])
    expect(totals()).toEqual([])
  })

  it('says why the average time waits although the completions are over the minimum group', async () => {
    server({ overview: [{ ...OVERVIEW_BIG, basedOn: 1, completions: 6, avgDurationSec: null, suppressed: false }],
      questions: QUESTIONS_BIG })
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    render(<App />)
    expect(await screen.findByTestId('overview-avg-duration-value')).toHaveTextContent('Hidden')
    expect(screen.getByTestId('overview-avg-duration'))
      .toHaveTextContent('Shown once at least 5 people have submitted in time.')
    // One completion: the singular.
    expect(screen.getByTestId('overview-k')).toHaveTextContent(/^Based on 1 completion\.$/)
  })

  it('offers to try again when the overview cannot be read', async () => {
    server({ overviewError: true })
    window.history.replaceState(null, '', `/publications/${P1}/analytics`)
    render(<App />)
    expect(await screen.findByText('The figures could not be loaded.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.queryByTestId('publication-analytics')).toBeNull()
  })
})
