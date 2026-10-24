import { describe, expect, it } from 'vitest'
import { trustedPlayer, videoPlayer } from './video'

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
  ])('shows %j as no player', (url) => {
    expect(videoPlayer(url)).toBeNull()
  })

  it('trusts only a player address as the server gives it', () => {
    expect(trustedPlayer('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'))
      .toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
    expect(trustedPlayer('https://player.vimeo.com/video/76979871')).toBe('https://player.vimeo.com/video/76979871')
    expect(trustedPlayer('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBeNull()
    expect(trustedPlayer('https://evil.example/embed/dQw4w9WgXcQ')).toBeNull()
    expect(trustedPlayer(null)).toBeNull()
  })
})
