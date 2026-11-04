import { describe, expect, it } from 'vitest'
import { autoplaying, trackable, trustedPlayer, videoPlayer } from './video'

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

  it('starts by itself only a player that says where it is (Q99)', () => {
    expect(autoplaying('https://player.vimeo.com/video/76979871')).toBe('https://player.vimeo.com/video/76979871?autoplay=1&muted=1&playsinline=1')
    const bilibili = 'https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0'
    expect(autoplaying(bilibili)).toBe(bilibili)
    expect(trackable(null)).toBe(false)
  })

  it('trusts only a player address as the server gives it', () => {
    expect(trustedPlayer('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'))
      .toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
    expect(trustedPlayer('https://player.vimeo.com/video/76979871')).toBe('https://player.vimeo.com/video/76979871')
    expect(trustedPlayer('https://www.dailymotion.com/embed/video/x7tgad0')).toBe('https://www.dailymotion.com/embed/video/x7tgad0')
    expect(trustedPlayer('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBeNull()
    expect(trustedPlayer('https://www.bilibili.com/video/BV1GJ411x7h7')).toBeNull()
    expect(trustedPlayer('https://evil.example/embed/dQw4w9WgXcQ')).toBeNull()
    expect(trustedPlayer(null)).toBeNull()
  })
})
