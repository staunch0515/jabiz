import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../routes'
import { mockPublicApi } from '../test/api'
import { renderRoutes } from '../test/render'

afterEach(() => vi.unstubAllGlobals())

const places = [
  { slug: 'japan', name: { en: 'Japan', ja: '日本' }, countryCode: 'JP', latitude: 35.7, longitude: 139.7, participantCount: 1, storyCount: 2 },
  { slug: 'sweden', name: { en: 'Sweden' }, countryCode: 'SE', latitude: 59.3, longitude: 18.1, participantCount: 1, storyCount: 1 },
  { slug: 'club', name: { en: 'Tromsø youth club' }, countryCode: null, latitude: null, longitude: null, participantCount: 0, storyCount: 0 },
]

function japanPanel() {
  return {
    'culture.public.locations': () => places,
    'culture.public.people': (s: URLSearchParams) =>
      s.get('p.location') === 'japan' ? [{ slug: 'aiko', displayName: 'Aiko', portraitFileId: null }] : [],
    'culture.public.location_themes': () => [{ slug: 'home', icon: '🏠', title: { en: 'Home' }, storyCount: 2 }],
    'culture.public.stories': () => [{ slug: 'trains', title: { en: 'Trains at night' }, mediaType: 'VIDEO' }],
    'culture.public.location_media': () => [
      { itemId: 'v1', kind: 'VIDEO', storySlug: 'trains', storyTitle: { en: 'Trains at night' }, displayName: 'Aiko', imageFileId: 'f1' },
      { itemId: 'p1', kind: 'PHOTO', storySlug: 'trains', storyTitle: { en: 'Trains at night' }, displayName: 'Aiko', imageFileId: 'f2', alt: { en: 'A platform at dusk' } },
    ],
  }
}

describe('map page (design section 9.5)', () => {
  it('has a button for every place with a position and a list with every place', async () => {
    mockPublicApi(japanPanel())
    renderRoutes(routes, { path: '/en/map' })
    const markers = await screen.findByRole('list', { name: 'Places on the map' })
    await waitFor(() => expect(within(markers).getAllByRole('button')).toHaveLength(2))
    expect(within(markers).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([
      'Japan: 1 person, 2 stories',
      'Sweden: 1 person, 1 story',
    ])
    const list = screen.getByRole('heading', { level: 2, name: 'Places' }).nextElementSibling as HTMLElement
    expect(within(list).getAllByRole('button')).toHaveLength(3)
    expect(within(list).getByRole('button', { name: /Tromsø youth club.*Not on the map/ })).toBeInTheDocument()
    expect(screen.getByText(/Choose a place on the map or in the list/)).toBeInTheDocument()
  })

  it('shows what is published from a place, chosen with the keyboard on the map, and keeps it in the address', async () => {
    const api = mockPublicApi(japanPanel())
    const { router } = renderRoutes(routes, { path: '/en/map' })
    const marker = await screen.findByRole('button', { name: 'Japan: 1 person, 2 stories' })
    marker.focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(router.state.location.search).toBe('?place=japan'))
    expect(marker).toHaveAttribute('aria-pressed', 'true')
    // The focus stays on the map; the choice is announced.
    expect(marker).toHaveFocus()
    expect(screen.getAllByRole('status').some((s) => s.textContent === 'Japan: 1 person, 2 stories')).toBe(true)

    const panel = screen.getByRole('complementary', { name: 'Japan: 1 person, 2 stories' })
    expect(within(panel).getByRole('heading', { level: 2, name: /Japan/ })).toBeInTheDocument()
    expect(await within(panel).findByRole('link', { name: 'Aiko' })).toHaveAttribute('href', '/en/people/aiko')
    expect(within(panel).getByRole('link', { name: /Home/ })).toHaveAttribute('href', '/en/themes/home')
    expect(within(panel).getByRole('link', { name: 'All stories with someone from Japan →' })).toHaveAttribute(
      'href',
      '/en/stories?place=japan',
    )
    const videos = within(panel).getByRole('heading', { level: 3, name: 'Videos' }).parentElement!
    expect(within(videos).getByRole('link', { name: /Trains at night/ })).toHaveAttribute('href', '/en/stories/trains')
    const photos = within(panel).getByRole('heading', { level: 3, name: 'Photographs' }).parentElement!
    expect(within(photos).getByRole('img', { name: 'A platform at dusk' })).toBeInTheDocument()
    expect(api.of('culture.public.location_media').at(-1)!.searchParams.get('p.location')).toBe('japan')
  })

  it('from the list, takes the focus to the place it shows', async () => {
    mockPublicApi(japanPanel())
    renderRoutes(routes, { path: '/ja/map' })
    const list = (await screen.findByRole('heading', { level: 2, name: '場所' })).nextElementSibling as HTMLElement
    await userEvent.click(await within(list).findByRole('button', { name: /日本/ }))
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /日本/ })).toHaveFocus())
  })

  it('opens with the place in the address, and says when nothing is published from it', async () => {
    mockPublicApi({ 'culture.public.locations': () => places })
    renderRoutes(routes, { path: '/en/map?place=club' })
    expect(await screen.findByText('Nothing from here is published yet.')).toBeInTheDocument()
  })
})

/** Answers the search template as the server does: the outer filter on `kind`. */
function byKind(rows: { kind: string }[]) {
  return (search: URLSearchParams) => {
    const kind = /^kind:eq:(.*)$/.exec(search.get('filter') ?? '')?.[1]
    return rows.filter((row) => !kind || row.kind === kind).slice(0, Number(search.get('limit') ?? 100))
  }
}

describe('search page (brief section 14)', () => {
  const hits = [
    { kind: 'STORY', slug: 'trains', title: { en: 'Trains at night' }, summary: { en: 'Going home late.' } },
    { kind: 'PERSON', slug: 'aiko', name: 'Aiko', summary: { en: 'Interested in trains.' } },
    { kind: 'THEME', slug: 'home', title: { en: 'Home' } },
    { kind: 'RESOURCE', slug: 'mapping', title: { en: 'Mapping home' } },
  ]

  it('finds with the words in the address and groups what it finds', async () => {
    const api = mockPublicApi({ 'culture.public.search': byKind(hits) })
    renderRoutes(routes, { path: '/en/search?q=home' })
    await waitFor(() => expect(screen.getAllByRole('status').at(-1)).toHaveTextContent('4 results for “home”'))
    // One request per kind.
    expect(api.of('culture.public.search').map((u) => u.searchParams.get('filter')).sort()).toEqual(
      ['kind:eq:PERSON', 'kind:eq:RESOURCE', 'kind:eq:STORY', 'kind:eq:THEME'],
    )
    expect(api.of('culture.public.search')[0].searchParams.get('p.q')).toBe('home')
    expect(screen.getByRole('searchbox', { name: 'Words to search for' })).toHaveValue('home')
    for (const [group, name, href] of [
      ['Stories', 'Trains at night', '/en/stories/trains'],
      ['People', 'Aiko', '/en/people/aiko'],
      ['Themes', 'Home', '/en/themes/home'],
      ['Resources', 'Mapping home', '/en/resources/mapping'],
    ]) {
      const section = screen.getByRole('heading', { level: 2, name: new RegExp(`^${group}`) }).parentElement!
      expect(within(section).getByRole('link', { name })).toHaveAttribute('href', href)
    }
  })

  it('says when a kind has more results than it shows, and never hides the other kinds', async () => {
    const stories = Array.from({ length: 50 }, (_, i) => ({ kind: 'STORY', slug: `s${i}`, title: { en: `Story ${i}` } }))
    mockPublicApi({ 'culture.public.search': byKind([...stories, ...hits.slice(1)]) })
    renderRoutes(routes, { path: '/en/search?q=home' })
    await waitFor(() =>
      expect(screen.getAllByRole('status').at(-1)).toHaveTextContent('More than 53 results for “home”: add a word'),
    )
    expect(screen.getByRole('heading', { level: 2, name: /^Stories 50\+/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mapping home' })).toBeInTheDocument()
  })

  it('asks nothing for words over 100 characters (an address can hold them) and says why', async () => {
    const api = mockPublicApi({ 'culture.public.search': () => hits })
    renderRoutes(routes, { path: `/en/search?q=${'a'.repeat(101)}` })
    await waitFor(() => expect(screen.getAllByRole('status').at(-1)).toHaveTextContent('Use at most 100 characters.'))
    expect(api.of('culture.public.search')).toHaveLength(0)
  })

  it('puts new words in the address; too few characters ask nothing', async () => {
    const api = mockPublicApi({ 'culture.public.search': () => [] })
    const { router } = renderRoutes(routes, { path: '/en/search' })
    const box = await screen.findByRole('searchbox', { name: 'Words to search for' })
    await userEvent.type(box, 'a{Enter}')
    await waitFor(() => expect(router.state.location.search).toBe('?q=a'))
    expect(screen.getAllByRole('status').at(-1)).toHaveTextContent('Type at least 2 characters.')
    expect(api.of('culture.public.search')).toHaveLength(0)

    await userEvent.type(box, 'bc{Enter}')
    await waitFor(() => expect(router.state.location.search).toBe('?q=abc'))
    await waitFor(() => expect(screen.getAllByRole('status').at(-1)).toHaveTextContent('Nothing matches “abc” yet.'))
    expect(api.of('culture.public.search').at(-1)!.searchParams.get('p.q')).toBe('abc')
  })

  it('is in the header and the footer; the map is in the footer, not the main navigation', async () => {
    mockPublicApi({})
    renderRoutes(routes, { path: '/zh/' })
    const header = (await screen.findAllByRole('link', { name: '搜索' }))[0]
    expect(header).toHaveAttribute('href', '/zh/search')
    const main = screen.getByRole('navigation', { name: '主导航' })
    expect(within(main).queryByRole('link', { name: '地图' })).toBeNull()
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: '地图' })).toHaveAttribute('href', '/zh/map')
  })
})
