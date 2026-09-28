import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../routes'
import { mockPublicApi } from '../test/api'
import { renderRoutes } from '../test/render'

afterEach(() => vi.unstubAllGlobals())

const places = [
  { slug: 'japan', name: { en: 'Japan', ja: '日本' }, countryCode: 'JP', sortOrder: 1, participantCount: 1, storyCount: 1 },
  { slug: 'tromso', name: { en: 'Tromsø youth club' }, countryCode: null, sortOrder: 2, participantCount: 1, storyCount: 0 },
]
const themes = [{ slug: 'home', icon: '🏠', title: { en: 'Home' }, question: { en: 'What makes somewhere feel like home?' }, storyCount: 1, locationCount: 2 }]
const people = [
  { slug: 'chloe', displayName: 'Chloe', countryCode: 'JP', locationName: { en: 'Japan' }, shortBio: { en: 'Interested in identity.' } },
]
const stories = [{ slug: 'what-home-means', title: { en: 'What does home mean to us?' }, summary: { en: 'Six places.' }, mediaType: 'DIGITAL_ZINE', storyDate: '2026-03-14T00:00:00Z' }]

describe('routes (design section 9.1)', () => {
  it('sends / to the browser language', async () => {
    mockPublicApi({})
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['ja-JP', 'en'])
    const { router } = renderRoutes(routes, { path: '/' })
    await waitFor(() => expect(router.state.location.pathname).toBe('/ja/'))
  })

  it('shows "not found" for an unknown language or page, and for a slug that is not public', async () => {
    mockPublicApi({})
    const first = renderRoutes(routes, { path: '/fr/stories' })
    expect(await screen.findByRole('heading', { level: 1, name: 'We could not find this page' })).toBeInTheDocument()
    first.unmount()
    const second = renderRoutes(routes, { path: '/en/nowhere' })
    expect(await screen.findByRole('heading', { level: 1, name: 'We could not find this page' })).toBeInTheDocument()
    second.unmount()
    renderRoutes(routes, { path: '/zh/stories/draft-story' })
    expect(await screen.findByRole('heading', { level: 1, name: '没有找到这个页面' })).toBeInTheDocument()
  })
})

describe('home page', () => {
  it('takes every place, person, story and theme from the data (design section 0)', async () => {
    mockPublicApi({
      'culture.public.site_blocks': () => [
        { blockKey: 'home.hero.title', body: { en: 'CULTURE, UNFILTERED' } },
        { blockKey: 'home.answer', body: { en: "Probably not. That's why we're not trying to." } },
      ],
      'culture.public.locations': () => places,
      'culture.public.people': () => people,
      'culture.public.themes': () => themes,
      'culture.public.stories': () => stories,
    })
    renderRoutes(routes, { path: '/ja/' })
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('CULTURE, UNFILTERED'))
    const h1 = screen.getByRole('heading', { level: 1 })
    // The block has no Japanese: English, marked as such.
    expect(h1).toHaveAttribute('lang', 'en')
    const placeList = await screen.findByRole('list', { name: '場所' })
    expect(within(placeList).getByRole('link', { name: /日本/ })).toHaveAttribute('href', '/ja/stories?place=japan')
    // A place that is not a country: no flag, still listed.
    expect(within(placeList).getByRole('link', { name: 'Tromsø youth club' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'What does home mean to us?' })).toHaveAttribute('href', '/ja/stories/what-home-means')
    // A media type the site has no name for is shown, readably.
    expect(screen.getAllByText(/Digital zine/).length).toBeGreaterThan(0)
    expect(screen.getByText('Probably not.')).toHaveClass('mark')
    expect(document.documentElement.lang).toBe('ja')
  })
})

describe('home page, first screen (docs/culture/operations.md, Lighthouse)', () => {
  it('paints the title before any data, and nothing under it until the data is in', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
    renderRoutes(routes, { path: '/en/' })
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('CULTURE, UNFILTERED')
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
    expect(screen.queryByRole('list', { name: 'Places' })).toBeNull()
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull()
  })
})

describe('story archive (design section 9.3, FilterBar)', () => {
  it('keeps the filters in the address and sends them to the template', async () => {
    const api = mockPublicApi({
      'culture.public.locations': () => places,
      'culture.public.themes': () => themes,
      'culture.public.stories': (search) => (search.getAll('p.location').includes('tromso') ? [] : stories),
    })
    const { router } = renderRoutes(routes, { path: '/en/stories?place=japan' })
    expect(await screen.findByRole('checkbox', { name: /Japan/ })).toBeChecked()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('1 story'))
    expect(api.of('culture.public.stories').at(-1)!.searchParams.getAll('p.location')).toEqual(['japan'])

    await userEvent.click(screen.getByRole('checkbox', { name: 'Tromsø youth club' }))
    await waitFor(() => expect(router.state.location.search).toBe('?place=japan&place=tromso'))
    await waitFor(() => expect(api.of('culture.public.stories').at(-1)!.searchParams.getAll('p.location')).toEqual(['japan', 'tromso']))
    expect(await screen.findByText('No stories match these filters yet.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Video' }))
    await waitFor(() => expect(router.state.location.search).toContain('media=VIDEO'))
    await userEvent.click(screen.getByRole('button', { name: 'Clear all filters' }))
    await waitFor(() => expect(router.state.location.search).toBe(''))
    await waitFor(() => expect(api.of('culture.public.stories').at(-1)!.searchParams.has('p.location')).toBe(false))
  })
})

describe('theme page (brief section 8)', () => {
  const perspectives = ['Chloe', 'Maja', 'Elin'].map((name, i) => ({
    contributionId: `c${i}`,
    participantSlug: name.toLowerCase(),
    displayName: name,
    locationSlug: `place-${i}`,
    locationName: { en: `Place ${i}` },
    heading: { en: `${name}'s home` },
    text: { en: `What ${name} wrote.` },
    storySlug: 'what-home-means',
  }))

  it('compares perspectives side by side, or one at a time, and keeps the choice in the address', async () => {
    mockPublicApi({
      'culture.public.theme': () => [{ slug: 'home', icon: '🏠', title: { en: 'Home' }, question: { en: 'What makes somewhere feel like home?' } }],
      'culture.public.theme_perspectives': () => perspectives,
      'culture.public.stories': () => stories,
    })
    const { router } = renderRoutes(routes, { path: '/en/themes/home' })
    expect(await screen.findByRole('heading', { level: 1, name: 'What makes somewhere feel like home?' })).toBeInTheDocument()
    expect(await screen.findAllByRole('heading', { level: 2, name: /'s home$/ })).toHaveLength(3)
    expect(screen.getByRole('button', { name: 'Side by side' })).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(screen.getByRole('button', { name: 'One at a time' }))
    await waitFor(() => expect(router.state.location.search).toBe('?view=one'))
    expect(screen.getAllByRole('heading', { level: 2, name: /'s home$/ })).toHaveLength(1)
    expect(screen.getByText('1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Previous/ })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /Next/ }))
    expect(screen.getByRole('heading', { level: 2, name: "Maja's home" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Read in the story/ })).toHaveAttribute('href', '/en/stories/what-home-means#p-c1')
  })
})

describe('layout (design section 9.1)', () => {
  it('opens the menu with the focus in it; Esc closes it and returns the focus', async () => {
    mockPublicApi({})
    renderRoutes(routes, { path: '/en/method' })
    const button = await screen.findByRole('button', { name: 'Menu' })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    await waitFor(() => expect(document.activeElement).toHaveTextContent('Home'))
    await userEvent.keyboard('{Escape}')
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(document.activeElement).toBe(button)
  })

  it('switches language on the same page, keeping its filters', async () => {
    mockPublicApi({})
    renderRoutes(routes, { path: '/en/stories?theme=home' })
    const languages = await screen.findByRole('list', { name: 'Language' })
    expect(within(languages).getByRole('link', { name: '日本語' })).toHaveAttribute('href', '/ja/stories?theme=home')
    expect(within(languages).getByRole('link', { name: 'English' })).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('link', { name: 'Stories' })).toHaveAttribute('aria-current', 'page')
  })
})
