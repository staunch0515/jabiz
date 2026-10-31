/**
 * The video players the App shows (Q19; docs/quizbuks/plans/Q19-embedded-video.md), as the backend's
 * `content.VideoEmbeds` reads links: a YouTube video as its no-cookie player, a Vimeo video as Vimeo's. The App's
 * content security policy frames these two players only (`frame-src`); any other link stays a link.
 */
export const YOUTUBE_PLAYER = 'https://www.youtube-nocookie.com/embed/'
export const VIMEO_PLAYER = 'https://player.vimeo.com/video/'

const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/
const VIMEO_ID = /^[0-9]{1,12}$/
const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtube-nocookie.com',
  'www.youtube-nocookie.com'])
const VIMEO_HOSTS = new Set(['vimeo.com', 'www.vimeo.com', 'player.vimeo.com'])

const youtube = (id: string | null | undefined) => (id && YOUTUBE_ID.test(id) ? YOUTUBE_PLAYER + id : null)

/** The player's address for a YouTube or Vimeo video link; null for any other address. */
export function videoPlayer(url: string | null | undefined): string | null {
  if (!url || !url.trim()) return null
  let parsed: URL
  try {
    parsed = new URL(url.trim())
  } catch {
    return null
  }
  if ((parsed.protocol !== 'https:' && parsed.protocol !== 'http:') || parsed.username || parsed.password
    || parsed.port) return null
  const host = parsed.hostname.toLowerCase()
  const path = parsed.pathname.split('/')
  if (host === 'youtu.be') return youtube(path[1])
  if (YOUTUBE_HOSTS.has(host)) {
    if (path.length === 2 && path[1] === 'watch') return youtube(parsed.searchParams.get('v'))
    if (path.length === 3 && ['shorts', 'embed', 'live'].includes(path[1])) return youtube(path[2])
    return null
  }
  if (VIMEO_HOSTS.has(host)) {
    const id = host === 'player.vimeo.com' ? (path.length === 3 && path[1] === 'video' ? path[2] : null)
      : (path.length === 2 ? path[1] : null)
    return id && VIMEO_ID.test(id) ? VIMEO_PLAYER + id : null
  }
  return null
}

/**
 * The player started muted at once (Q63: the home's card in the middle of the screen, the start page): browsers let
 * a page start only a silent video by itself; the viewer turns the sound on in the player.
 */
export function autoplaying(player: string): string {
  const params = player.startsWith(VIMEO_PLAYER) ? 'autoplay=1&muted=1&playsinline=1'
    : 'autoplay=1&mute=1&playsinline=1'
  return `${player}?${params}`
}

/** Whether the viewer asked for less motion: then no video starts by itself (Q63). */
export function prefersStillVideo(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** A player address the server gave (the card's): only one of the two players, else none. */
export function trustedPlayer(address: string | null | undefined): string | null {
  if (!address) return null
  return videoPlayer(address) === address ? address : null
}
