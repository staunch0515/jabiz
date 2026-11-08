import { describe, expect, it } from 'vitest'
import { autoplaying, filePlayer, foreignVideo, isShorts, MAINLAND_SITES_DEFAULT, mainlandSites, pagePlayer, sitePlayer, trackable, trustedPlayer,
  verticalVideo, videoHost, videoKind, videoPlayer, videoSite } from './video'

/** The cases of the backend's `VideoEmbedsTest` (Q19): the App reads links as the server does. */
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
    expect(sitePlayer(url)).toBe(player)
    expect(videoKind(player)).toBe('site')
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
  ])('shows %j as no site\'s player', (url) => {
    // F-S15: some of these are another page (any https address), which is no site's player.
    expect(sitePlayer(url)).toBeNull()
    expect(videoSite(url)).toBeNull()
  })

  it.each([
    ['https://youtu.be/dQw4w9WgXcQ', true],
    ['https://vimeo.com/76979871', true],
    ['https://www.bilibili.com/video/BV1GJ411x7h7', false],
    ['https://nico.ms/sm9', false],
    ['https://dai.ly/x7tgad0', false],
    ['https://player.youku.com/embed/XNTk4MjQzMzQ0OA==', false],
    ['https://v.qq.com/x/page/x0029zdtwz6.html', false],
  ])('says whether the player of %s says how much was watched (Q99)', (url, expected) => {
    expect(trackable(videoPlayer(url))).toBe(expected)
  })

  it('starts every site\'s player muted the same way, where the site takes the parameters (Q99b)', () => {
    expect(autoplaying('https://player.vimeo.com/video/76979871')).toBe('https://player.vimeo.com/video/76979871?autoplay=1&muted=1&playsinline=1')
    expect(autoplaying('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'))
      .toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1&mute=1&playsinline=1')
    expect(autoplaying('https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0'))
      .toBe('https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=1&muted=1')
    expect(autoplaying('https://www.dailymotion.com/embed/video/x8abcd1')).toBe('https://www.dailymotion.com/embed/video/x8abcd1?autoplay=1&mute=1')
    expect(autoplaying('https://embed.nicovideo.jp/watch/sm9')).toBe('https://embed.nicovideo.jp/watch/sm9?autoplay=1&mute=1')
    // Youku's and Tencent Video's players take none: they show their own play button.
    expect(autoplaying('https://player.youku.com/embed/XNTk4MjQzMzQ0OA==')).toBe('https://player.youku.com/embed/XNTk4MjQzMzQ0OA==')
    expect(trackable(null)).toBe(false)
  })

  it('trusts only a player address as the server gives it', () => {
    expect(trustedPlayer('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'))
      .toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
    expect(trustedPlayer('https://player.vimeo.com/video/76979871')).toBe('https://player.vimeo.com/video/76979871')
    expect(trustedPlayer('https://www.dailymotion.com/embed/video/x7tgad0')).toBe('https://www.dailymotion.com/embed/video/x7tgad0')
    expect(trustedPlayer('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBeNull()
    expect(trustedPlayer('https://www.bilibili.com/video/BV1GJ411x7h7')).toBeNull()
    // F-S15: a page of another site and a video file are players as they are.
    expect(trustedPlayer('https://evil.example/embed/dQw4w9WgXcQ')).toBe('https://evil.example/embed/dQw4w9WgXcQ')
    expect(trustedPlayer('https://cdn.example.com/clip.mp4')).toBe('https://cdn.example.com/clip.mp4')
    expect(trustedPlayer('http://cdn.example.com/clip.mp4')).toBeNull()
    expect(trustedPlayer('HTTPS://CDN.example.com/clip.mp4')).toBeNull()
    expect(trustedPlayer(null)).toBeNull()
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
    expect([...mainland.sites].sort()).toEqual(['bilibili', 'tencent', 'youku'])
    expect(mainland.hosts.size).toBe(0)
    expect([...mainlandSites(' YouTube , nothing,,bilibili').sites].sort()).toEqual(['bilibili', 'youtube'])
    expect(mainlandSites('').sites.size).toBe(0)
    expect(foreignVideo(['https://v.qq.com/x/page/x0029zdtwz6.html'], { sites: new Set(['bilibili']), hosts: new Set() }))
      .toBe(true)
    expect(foreignVideo(['https://youtu.be/dQw4w9WgXcQ'], { sites: new Set(['youtube']), hosts: new Set() })).toBe(false)
    // F-S15: host names too (lower case); anything else is ignored.
    expect([...mainlandSites('bilibili, Videos.QuizBuks.com ,https://x.example,203.0.113.7,localhost').hosts])
      .toEqual(['videos.quizbuks.com'])
    expect(foreignVideo([], mainland)).toBe(false)
  })
})

/** F-S10: the cases of the backend's `VideoEmbedsTest.aShortsLinkIsVertical`. */
describe('isShorts and verticalVideo', () => {
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

  it('takes the sponsor\'s mark for any other video', () => {
    expect(isShorts(null)).toBe(false)
    expect(verticalVideo('https://www.bilibili.com/video/BV1GJ411x7h7', true)).toBe(true)
    expect(verticalVideo('https://www.bilibili.com/video/BV1GJ411x7h7', false)).toBe(false)
    expect(verticalVideo('https://www.youtube.com/shorts/dQw4w9WgXcQ', null)).toBe(true)
  })
})

/** F-S15: the cases of the backend's `VideoEmbedsTest` and `ForeignVideosTest` for links on any https address. */
describe('video files and pages of other sites', () => {
  it.each([
    ['https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4', 'file', 'https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4'],
    ['  https://cdn.example.com/a/b/clip.webm  ', 'file', 'https://cdn.example.com/a/b/clip.webm'],
    ['HTTPS://CDN.Example.com/Clip.MOV', 'file', 'https://cdn.example.com/Clip.MOV'],
    ['https://cdn.example.com/clip.mp4?sig=abc&exp=1', 'file', 'https://cdn.example.com/clip.mp4?sig=abc&exp=1'],
    ['https://cdn.example.com/clip.mp4#t=10', 'file', 'https://cdn.example.com/clip.mp4#t=10'],
    ['https://cdn.example.com/%E5%BE%AE.mp4', 'file', 'https://cdn.example.com/%E5%BE%AE.mp4'],
    ['https://video.example/capitals', 'page', 'https://video.example/capitals'],
    ['https://Player.Example.com/embed/123?autoplay=0', 'page', 'https://player.example.com/embed/123?autoplay=0'],
    ['https://evil.example/watch?v=dQw4w9WgXcQ', 'page', 'https://evil.example/watch?v=dQw4w9WgXcQ'],
    ['https://cdn.example.com/clip.mp4/', 'page', 'https://cdn.example.com/clip.mp4/'],
    ['https://cdn.example.com', 'page', 'https://cdn.example.com'],
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'site', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'],
    ['https://v.qq.com/x/page/x0029zdtwz6.html', 'site', 'https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6'],
  ] as const)('plays %s as a %s', (url, kind, player) => {
    expect(videoPlayer(url)).toBe(player)
    expect(videoKind(player)).toBe(kind)
    expect(trackable(player)).toBe(kind === 'file' || player.includes('youtube'))
    expect(videoPlayer(player)).toBe(player)
    if (kind !== 'site') {
      expect(videoSite(url)).toBeNull()
      expect(videoHost(url)).toBe(new URL(player).hostname)
    }
  })

  it.each([
    'http://cdn.example.com/clip.mp4', 'http://video.example/capitals', 'ftp://cdn.example.com/clip.mp4',
    'https://user@cdn.example.com/clip.mp4', 'https://user:pw@video.example/capitals',
    'https://cdn.example.com:8443/clip.mp4', 'https://video.example:443/capitals',
    'https://203.0.113.7/clip.mp4', 'https://localhost/clip.mp4', 'https://-cdn.example.com/clip.mp4',
    'https://cdn.example.com/a/../clip.mp4', 'https://cdn.example.com/a/./clip.mp4',
    'https://cdn.example.com//clip.mp4', 'https://cdn.example.com/a b.mp4', 'https://cdn.example.com/a"b.mp4',
    'https://cdn.example.com/<script>.mp4', 'https://cdn.example.com/a%2.mp4', 'https://cdn.example.com/a\\b.mp4',
    '//cdn.example.com/clip.mp4', 'cdn.example.com/clip.mp4', 'javascript:alert(1)', '', '   ',
    'https://www.youtube.com/@channel', 'https://www.youtube.com/', 'https://youtu.be/',
    'https://vimeo.com/channels/staffpicks', 'https://b23.tv/BV1GJ411x7h7', 'https://www.bilibili.com/',
    'https://www.youtube.com/clip.mp4',
  ])('plays no video at %j', (url) => {
    expect(videoPlayer(url)).toBeNull()
    expect(filePlayer(url)).toBeNull()
    expect(pagePlayer(url)).toBeNull()
    expect(videoKind(url)).toBeNull()
  })

  it.each([
    ['https://videos.quizbuks.com/quizbuks-demo-30s.mp4', false],
    ['https://VIDEOS.quizbuks.com/a/b.webm?sig=1', false],
    ['https://videos.quizbuks.com/player/1', false],
    ['https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4', true],
    ['https://cdn.example.com/clip.mp4', true],
    ['http://videos.quizbuks.com/quizbuks-demo-30s.mp4', true],
    ['https://youtu.be/dQw4w9WgXcQ', true],
    ['https://www.bilibili.com/video/BV1GJ411x7h7', false],
    ['https://www.youtube.com/@channel', true],
  ] as const)('says whether %s is foreign when its host is listed: %s', (url, foreign) => {
    const accessible = mainlandSites(`${MAINLAND_SITES_DEFAULT},videos.quizbuks.com,www.youtube.com`)
    expect(foreignVideo([url], accessible)).toBe(foreign)
    expect(foreignVideo([url], mainlandSites(null))).toBe(foreign || !url.includes('bilibili'))
  })
})

