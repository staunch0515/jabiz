import { describe, expect, it } from 'vitest'
import { cardVideoVertical, foreignVideo, isShorts, mainlandSites, reachesMainland, snapshotVideoLinks, videoPlayer, videoSite } from './video'
import type { Snapshot } from './snapshot'

/** The cases of the App's `qb/video.test.ts` (the backend's `VideoEmbedsTest` and `ForeignVideosTest`). */
describe('videoPlayer', () => {
  it.each([
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['https://m.youtube.com/watch?v=dQw4w9WgXcQ', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['http://youtu.be/dQw4w9WgXcQ?si=abc', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['https://www.youtube.com/shorts/dQw4w9WgXcQ', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['https://www.youtube.com/embed/dQw4w9WgXcQ', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['https://www.youtube.com/live/dQw4w9WgXcQ', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['https://vimeo.com/76979871', 'https://player.vimeo.com/video/76979871'],
    ['https://player.vimeo.com/video/76979871', 'https://player.vimeo.com/video/76979871'],
    ['  https://vimeo.com/76979871  ', 'https://player.vimeo.com/video/76979871'],
    // Q99: the other sites.
    ['https://www.bilibili.com/video/BV1GJ411x7h7', 'https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0'],
    ['https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.1007', 'https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0'],
    ['https://m.bilibili.com/video/BV1GJ411x7h7', 'https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0'],
    ['https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0', 'https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0'],
    ['https://www.nicovideo.jp/watch/sm9', 'https://embed.nicovideo.jp/watch/sm9'],
    ['https://sp.nicovideo.jp/watch/sm9?ref=share', 'https://embed.nicovideo.jp/watch/sm9'],
    ['https://nico.ms/sm9', 'https://embed.nicovideo.jp/watch/sm9'],
    ['https://embed.nicovideo.jp/watch/sm9', 'https://embed.nicovideo.jp/watch/sm9'],
    ['https://www.dailymotion.com/video/x7tgad0', 'https://www.dailymotion.com/embed/video/x7tgad0'],
    ['https://dai.ly/x7tgad0', 'https://www.dailymotion.com/embed/video/x7tgad0'],
    ['https://www.dailymotion.com/embed/video/x7tgad0', 'https://www.dailymotion.com/embed/video/x7tgad0'],
    ['https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==.html?spm=a2h0c', 'https://player.youku.com/embed/XNTk4MjQzMzQ0OA=='],
    ['https://player.youku.com/embed/XNTk4MjQzMzQ0OA==', 'https://player.youku.com/embed/XNTk4MjQzMzQ0OA=='],
    ['https://v.qq.com/x/page/x0029zdtwz6.html', 'https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6'],
    ['https://v.qq.com/x/cover/mzc00200abcdefg/x0029zdtwz6.html', 'https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6'],
    ['https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6', 'https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6'],
  ])('shows %s as its player', (url, player) => {
    expect(videoPlayer(url)).toBe(player)
  })

  it.each([
    '', '  ', 'not a link', 'ftp://youtube.com/watch?v=dQw4w9WgXcQ', 'javascript:alert(1)',
    'https://www.youtube.com/', 'https://www.youtube.com/watch', 'https://www.youtube.com/watch?v=short',
    'https://www.youtube.com/watch?v=dQw4w9WgXcQ%22onload', 'https://www.youtube.com/playlist?list=PL123',
    'https://www.youtube.com/@channel', 'https://youtu.be/', 'https://vimeo.com/channels/staffpicks',
    'https://vimeo.com/76979871/extra', 'https://vimeo.com/abc', 'https://evil.example/watch?v=dQw4w9WgXcQ',
    'https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ', 'https://user@youtube.com/watch?v=dQw4w9WgXcQ',
    'https://youtube.com:8443/watch?v=dQw4w9WgXcQ',
    'https://vimeo.com/76979871/', 'https://www.youtube.com/embed/dQw4w9WgXcQ/', 'https://www.youtube.com/watch/?v=dQw4w9WgXcQ',
    'https://www.youtube.com/embed/dQw4w9WgX%63Q',
    // Q99: not one video of the other sites.
    'https://www.bilibili.com/', 'https://www.bilibili.com/video/av170001', 'https://www.bilibili.com/video/BV1GJ411x7h',
    'https://space.bilibili.com/BV1GJ411x7h7', 'https://b23.tv/BV1GJ411x7h7',
    'https://player.bilibili.com/player.html?aid=170001', 'https://www.bilibili.com/video/BV1GJ411x7h7/x',
    'https://www.nicovideo.jp/watch/lv123', 'https://www.nicovideo.jp/user/123', 'https://nico.ms/',
    'https://www.dailymotion.com/playlist/x6hynp', 'https://www.dailymotion.com/video/X7TGAD0',
    'https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==', 'https://v.youku.com/v_show/id_<x>.html',
    'https://v.qq.com/x/page/x0029zdtwz6', 'https://v.qq.com/x/cover/mzc00200abcdefg.html',
    'https://v.qq.com.evil.example/x/page/x0029zdtwz6.html', 'https://evil.example/txp/iframe/player.html?vid=x0029zdtwz6',
  ])('shows %j as no player', (url) => {
    expect(videoPlayer(url)).toBeNull()
  })

})

/** F-S11: the cases of the backend's `VideoEmbedsTest.aVideoLinkHasItsSite` and `ForeignVideosTest`. */
describe('videoSite and foreignVideo', () => {
  it.each([
    ['https://youtu.be/dQw4w9WgXcQ', 'youtube'],
    ['https://www.youtube.com/shorts/dQw4w9WgXcQ', 'youtube'],
    ['https://vimeo.com/76979871', 'vimeo'],
    ['https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.1007', 'bilibili'],
    ['https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0', 'bilibili'],
    ['https://nico.ms/sm9', 'niconico'],
    ['https://dai.ly/x7tgad0', 'dailymotion'],
    ['https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==.html?spm=a2h0c', 'youku'],
    ['https://player.youku.com/embed/XNTk4MjQzMzQ0OA==', 'youku'],
    ['https://v.qq.com/x/cover/mzc00200abcdefg/x0029zdtwz6.html', 'tencent'],
    ['https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6', 'tencent'],
    ['https://b23.tv/BV1GJ411x7h7', null],
    ['https://video.example/capitals', null],
    ['https://www.bilibili.com/', null],
    ['https://v.qq.com.evil.example/x/page/x0029zdtwz6.html', null],
    ['', null],
  ])('says the site of %s is %s', (url, site) => {
    expect(videoSite(url)).toBe(site)
  })

  const mainland = mainlandSites(null)

  it.each([
    ['https://youtu.be/dQw4w9WgXcQ', true],
    ['https://vimeo.com/76979871', true],
    ['https://nico.ms/sm9', true],
    ['https://dai.ly/x7tgad0', true],
    ['https://www.bilibili.com/video/BV1GJ411x7h7', false],
    ['https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==.html?spm=a2h0c', false],
    ['https://v.qq.com/x/page/x0029zdtwz6.html', false],
    ['https://video.example/capitals', true],
    ['https://b23.tv/BV1GJ411x7h7', true],
    ['https://www.bilibili.com/', true],
    ['', false],
    ['   ', false],
  ])('says whether %j is foreign: %s', (url, foreign) => {
    expect(foreignVideo([url], mainland)).toBe(foreign)
  })

  it('reads the parameter as the server does', () => {
    expect([...mainland].sort()).toEqual(['bilibili', 'tencent', 'youku'])
    expect([...mainlandSites(' YouTube , nothing,,bilibili')].sort()).toEqual(['bilibili', 'youtube'])
    expect(mainlandSites('').size).toBe(0)
    expect(foreignVideo(['https://v.qq.com/x/page/x0029zdtwz6.html'], new Set(['bilibili']))).toBe(true)
    expect(foreignVideo(['https://youtu.be/dQw4w9WgXcQ'], new Set(['youtube']))).toBe(false)
    expect(foreignVideo([], mainland)).toBe(false)
  })
})

describe('a version and its audience', () => {
  it('takes every language\'s video links, and says which regions reach mainland China', () => {
    const material = (no: number, kind: string, url: string | null) => ({ no, kind, url, title: null, description: null,
      body: null, pdf: null, audio: null, images: [] })
    const content = { materials: [material(1, 'VIDEO_LINK', 'https://www.bilibili.com/video/BV1GJ411x7h7'),
      material(2, 'LINK', 'https://youtu.be/dQw4w9WgXcQ')],
    translations: { en: { texts: {}, images: {}, materials: [material(1, 'VIDEO_LINK', 'https://youtu.be/dQw4w9WgXcQ')] } },
    } as unknown as Snapshot
    expect(snapshotVideoLinks(content)).toEqual(['https://www.bilibili.com/video/BV1GJ411x7h7',
      'https://youtu.be/dQw4w9WgXcQ'])
    expect(foreignVideo(snapshotVideoLinks(content), mainlandSites(null))).toBe(true)
    expect(reachesMainland('PUBLIC', ['JP'])).toBe(false)
    expect(reachesMainland('PUBLIC', ['ASIA'])).toBe(true)
    expect(reachesMainland('PUBLIC', ['GLOBAL'])).toBe(true)
    expect(reachesMainland('INVITED', [])).toBe(true)
  })
})

/** F-S10: the cases of the backend's `VideoEmbedsTest.aShortsLinkIsVertical`, and the card's choice of its video. */
describe('isShorts and cardVideoVertical', () => {
  it.each([
    ['https://www.youtube.com/shorts/dQw4w9WgXcQ', true],
    ['https://m.youtube.com/shorts/dQw4w9WgXcQ?feature=share', true],
    ['  https://youtube.com/shorts/dQw4w9WgXcQ  ', true],
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ', false],
    ['https://youtu.be/dQw4w9WgXcQ', false],
    ['https://www.youtube.com/embed/dQw4w9WgXcQ', false],
    ['https://www.youtube.com/shorts/short', false],
    ['https://www.youtube.com/shorts/dQw4w9WgXcQ/', false],
    ['https://evil.example/shorts/dQw4w9WgXcQ', false],
    ['https://vimeo.com/76979871', false],
    ['https://www.bilibili.com/video/BV1GJ411x7h7', false],
    ['', false],
  ])('says whether %j is a Shorts link: %s', (url, shorts) => {
    expect(isShorts(url)).toBe(shorts)
  })

  const video = (url: string, more: Partial<Snapshot['materials'][number]> = {}) => ({ no: 1, kind: 'VIDEO_LINK' as const,
    title: null, description: null, body: null, url, pdf: null, audio: null, images: [], watchToAnswer: false,
    durationSec: null, ...more })
  const version = (...materials: Snapshot['materials']) => ({ materials } as unknown as Snapshot)

  it('takes the card\'s video as the server chooses it', () => {
    expect(cardVideoVertical(version(video('https://www.youtube.com/shorts/dQw4w9WgXcQ')))).toBe(true)
    expect(cardVideoVertical(version(video('https://www.bilibili.com/video/BV1GJ411x7h7', { vertical: true })))).toBe(true)
    expect(cardVideoVertical(version(video('https://www.bilibili.com/video/BV1GJ411x7h7')))).toBe(false)
    // The video to watch comes first; else the first with a player.
    expect(cardVideoVertical(version(video('https://www.youtube.com/shorts/dQw4w9WgXcQ'),
      video('https://vimeo.com/76979871', { watchToAnswer: true })))).toBe(false)
    expect(cardVideoVertical(version(video('https://video.example/x'),
      video('https://www.youtube.com/shorts/dQw4w9WgXcQ')))).toBe(true)
    expect(cardVideoVertical(version())).toBe(false)
  })
})
