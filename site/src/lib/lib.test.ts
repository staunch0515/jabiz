import { describe, expect, it } from 'vitest'
import { fileUrl, queryString } from '../api/client'
import { formatDate } from './dates'
import { clearFilters, listParam, readFilters, writeFilter } from './filters'
import { flag } from './flags'
import { srcSet } from './images'
import { humanize } from './labels'
import { isExternal } from './links'
import { pick, pickText, preferredLocale } from './localized'
import { path } from './paths'
import { embedUrl } from './video'

describe('language fallback (design section 9.3, LocalizedText)', () => {
  it('takes the interface language when it has text', () => {
    expect(pick({ en: 'Home', ja: '家' }, 'ja')).toEqual({ text: '家', lang: 'ja', fallback: false })
  })

  it('falls back to English, then to any language, and says which', () => {
    expect(pick({ en: 'Home', ja: '家' }, 'zh')).toEqual({ text: 'Home', lang: 'en', fallback: true })
    expect(pick({ ja: '家' }, 'zh')).toEqual({ text: '家', lang: 'ja', fallback: true })
  })

  it('treats blank text as missing', () => {
    expect(pick({ zh: '  ', en: 'Home' }, 'zh')?.lang).toBe('en')
    expect(pick({ en: '' }, 'en')).toBeNull()
    expect(pick(null, 'en')).toBeNull()
    expect(pickText(undefined, 'en')).toBe('')
  })

  it('chooses the browser language among ours, English otherwise', () => {
    expect(preferredLocale(['ja-JP', 'en'])).toBe('ja')
    expect(preferredLocale(['zh-Hant-TW'])).toBe('zh')
    expect(preferredLocale(['fr-FR', 'de'])).toBe('en')
    expect(preferredLocale([])).toBe('en')
  })
})

describe('video embeds (design section 9.3)', () => {
  it('builds YouTube addresses on the no-cookie host with subtitles in the interface language', () => {
    const url = new URL(embedUrl('YOUTUBE', 'dQw4w9WgXcQ', 'ja')!)
    expect(url.origin).toBe('https://www.youtube-nocookie.com')
    expect(url.pathname).toBe('/embed/dQw4w9WgXcQ')
    expect(url.searchParams.get('cc_load_policy')).toBe('1')
    expect(url.searchParams.get('hl')).toBe('ja')
    expect(url.searchParams.get('rel')).toBe('0')
  })

  it('builds Vimeo addresses with do-not-track', () => {
    const url = new URL(embedUrl('VIMEO', '76979871', 'en')!)
    expect(url.origin).toBe('https://player.vimeo.com')
    expect(url.searchParams.get('dnt')).toBe('1')
  })

  it('refuses other providers and ids that are not plain ids', () => {
    expect(embedUrl('DAILYMOTION', 'x7tgad0', 'en')).toBeNull()
    expect(embedUrl('YOUTUBE', '../../evil', 'en')).toBeNull()
    expect(embedUrl('YOUTUBE', 'abc?x=1&y', 'en')).toBeNull()
    expect(embedUrl('YOUTUBE', 'short', 'en')).toBeNull()
    expect(embedUrl('YOUTUBE', null, 'en')).toBeNull()
  })
})

describe('filters in the address (design section 9.3, FilterBar)', () => {
  it('reads repeated parameters, without duplicates or blanks', () => {
    const search = new URLSearchParams('place=japan&place=poland&place=japan&theme=&media=VIDEO&other=1')
    expect(readFilters(search, ['place', 'theme', 'media'])).toEqual({ place: ['japan', 'poland'], theme: [], media: ['VIDEO'] })
  })

  it('replaces one group and keeps the other parameters', () => {
    const search = new URLSearchParams('place=japan&view=one')
    expect(writeFilter(search, 'place', ['sweden', 'uk']).toString()).toBe('view=one&place=sweden&place=uk')
    expect(writeFilter(search, 'place', []).toString()).toBe('view=one')
    expect(clearFilters(new URLSearchParams('place=a&theme=b&view=one'), ['place', 'theme']).toString()).toBe('view=one')
  })

  it('leaves out list parameters with no choice, so that the template does not filter', () => {
    expect(listParam([])).toBeUndefined()
    expect(listParam(['a'])).toEqual(['a'])
  })
})

describe('the public interface (docs/design/15-public-access.md section 5)', () => {
  it('sends parameters as p.<name>, lists repeated, then filters, sort and paging', () => {
    const qs = queryString<'culture.public.stories'>(
      { theme: ['home', 'food'], q: 'a b', location: undefined },
      { filters: [{ field: 'mediaType', op: 'in', value: ['VIDEO', 'PHOTO'] }], sort: { field: 'storyDate', direction: 'desc' }, limit: 20 },
    )
    const search = new URLSearchParams(qs)
    expect(search.getAll('p.theme')).toEqual(['home', 'food'])
    expect(search.get('p.q')).toBe('a b')
    expect(search.has('p.location')).toBe(false)
    expect(search.get('filter')).toBe('mediaType:in:VIDEO,PHOTO')
    expect(search.get('sort')).toBe('storyDate:desc')
    expect(search.get('limit')).toBe('20')
    expect(search.get('count')).toBe('false')
  })

  it('addresses files and their variants', () => {
    expect(fileUrl('0190-a')).toBe('/api/public/files/0190-a')
    expect(fileUrl('0190-a', 'w640')).toBe('/api/public/files/0190-a/w640')
    expect(srcSet('f')).toBe('/api/public/files/f/w320 320w, /api/public/files/f/w640 640w, /api/public/files/f/w1280 1280w')
  })
})

describe('small helpers', () => {
  it('shows a flag only for a country code', () => {
    expect(flag('JP')).toBe('🇯🇵')
    expect(flag('SZ')).toBe('🇸🇿')
    expect(flag(null)).toBe('')
    expect(flag('jp')).toBe('')
  })

  it('names dictionary values the site does not know', () => {
    expect(humanize('DIGITAL_ZINE')).toBe('Digital zine')
    expect(humanize('11-13')).toBe('11 13')
  })

  it('tells external links from the site own', () => {
    expect(isExternal('https://example.org/x', 'https://culture.test')).toBe(true)
    expect(isExternal('/en/method', 'https://culture.test')).toBe(false)
    expect(isExternal('https://culture.test/en', 'https://culture.test')).toBe(false)
    expect(isExternal(undefined)).toBe(false)
  })

  it('builds addresses with the language first', () => {
    expect(path('ja')).toBe('/ja/')
    expect(path('en', 'stories', 'what home means')).toBe('/en/stories/what%20home%20means')
    expect(path('zh', 'method')).toBe('/zh/method')
  })

  it('formats dates in the interface language without shifting the day', () => {
    expect(formatDate('2026-03-14T00:00:00Z', 'en')).toBe('Mar 14, 2026')
    expect(formatDate('2026-03-14T00:00:00Z', 'ja')).toContain('2026')
    expect(formatDate(null, 'en')).toBe('')
  })
})
