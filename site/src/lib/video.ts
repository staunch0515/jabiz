/**
 * Embed addresses of the two video players the site allows (design section 9.3): only a provider and a video id are
 * stored, and the address is built here, so that no content can point the page at another site. The
 * Content-Security-Policy's frame-src names the same two hosts.
 */
export type VideoProvider = 'YOUTUBE' | 'VIMEO'

const VIDEO_ID = /^[A-Za-z0-9_-]{6,32}$/

export function embedUrl(provider: string | null | undefined, videoId: string | null | undefined, locale: string): string | null {
  if (!videoId || !VIDEO_ID.test(videoId)) return null
  const id = encodeURIComponent(videoId)
  const lang = encodeURIComponent(locale)
  switch (provider) {
    case 'YOUTUBE':
      return `https://www.youtube-nocookie.com/embed/${id}?autoplay=1&cc_load_policy=1&cc_lang_pref=${lang}&hl=${lang}&rel=0`
    case 'VIMEO':
      return `https://player.vimeo.com/video/${id}?autoplay=1&dnt=1&texttrack=${lang}`
    default:
      return null
  }
}

export function providerName(provider: string | null | undefined): string {
  return provider === 'VIMEO' ? 'Vimeo' : 'YouTube'
}
