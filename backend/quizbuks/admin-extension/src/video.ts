/**
 * F-S11: which video links mainland China cannot play, for the review page's notice: a copy of the App's
 * `qb/video.ts` (`videoPlayer`, `videoSite`, `foreignVideo`; the backend's `VideoEmbeds` and `ForeignVideos`), with the
 * same cases in `video.test.ts`. Change them together.
 */
import type { Snapshot } from './snapshot'

const YOUTUBE_PLAYER = 'https://www.youtube-nocookie.com/embed/'
const VIMEO_PLAYER = 'https://player.vimeo.com/video/'
const BILIBILI_PLAYER = 'https://player.bilibili.com/player.html?bvid='
/** Bilibili's player starts by itself unless told not to. */
const BILIBILI_SUFFIX = '&autoplay=0'
const NICONICO_PLAYER = 'https://embed.nicovideo.jp/watch/'
const DAILYMOTION_PLAYER = 'https://www.dailymotion.com/embed/video/'
const YOUKU_PLAYER = 'https://player.youku.com/embed/'
const TENCENT_PLAYER = 'https://v.qq.com/txp/iframe/player.html?vid='

const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/
const VIMEO_ID = /^[0-9]{1,12}$/
const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtube-nocookie.com',
  'www.youtube-nocookie.com'])
const VIMEO_HOSTS = new Set(['vimeo.com', 'www.vimeo.com', 'player.vimeo.com'])
const BILIBILI_ID = /^BV[0-9A-Za-z]{10}$/
const BILIBILI_HOSTS = new Set(['bilibili.com', 'www.bilibili.com', 'm.bilibili.com'])
const NICONICO_ID = /^(sm|nm|so)[0-9]{1,12}$/
const NICONICO_HOSTS = new Set(['nicovideo.jp', 'www.nicovideo.jp', 'sp.nicovideo.jp', 'embed.nicovideo.jp'])
const DAILYMOTION_ID = /^x[0-9a-z]{4,12}$/
const DAILYMOTION_HOSTS = new Set(['dailymotion.com', 'www.dailymotion.com'])
const YOUKU_ID = /^[A-Za-z0-9]{8,28}={0,2}$/
const TENCENT_ID = /^[a-z0-9]{11}$/
const TENCENT_COVER = /^[a-z0-9]{15}$/

const match = (id: RegExp, value: string | null | undefined, player: string, suffix = '') =>
  (value && id.test(value) ? player + value + suffix : null)
const html = (segment: string | undefined) => (segment && segment.endsWith('.html') ? segment.slice(0, -5) : null)

const youtube = (id: string | null | undefined) => (id && YOUTUBE_ID.test(id) ? YOUTUBE_PLAYER + id : null)

/**
 * F-S10: whether a video link is a YouTube Shorts link, read as `videoPlayer` reads links (the backend's
 * `VideoEmbeds.shorts`, the same cases).
 */
export function isShorts(url: string | null | undefined): boolean {
  if (!videoPlayer(url)) return false
  const parsed = new URL(url!.trim())
  const path = parsed.pathname.split('/')
  return YOUTUBE_HOSTS.has(parsed.hostname.toLowerCase()) && path.length === 3 && path[1] === 'shorts'
}

/**
 * F-S10: whether the card of a version's publication would show a vertical video, as the backend's
 * `PublicationEditing.cardVideo` chooses it: the video to watch (YouTube or Vimeo), else the first video link with a
 * player; vertical when it is a YouTube Shorts link or the sponsor marked it.
 */
export function cardVideoVertical(content: Snapshot): boolean {
  const trackable = (url: string | null) => {
    const player = videoPlayer(url)
    return !!player && (player.startsWith(YOUTUBE_PLAYER) || player.startsWith(VIMEO_PLAYER))
  }
  const watched = content.materials.find((material) => material.watchToAnswer)
  const card = watched && trackable(watched.url) ? watched
    : content.materials.find((material) => material.kind === 'VIDEO_LINK' && !!videoPlayer(material.url))
  return !!card && (card.vertical === true || isShorts(card.url))
}

/** The player's address for a link to one video of the sites above; null for any other address. */
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
  const query = (name: string) => parsed.searchParams.get(name)
  if (BILIBILI_HOSTS.has(host)) {
    // bilibili.com/video/<BV id>, its trailing slash too (as Bilibili shares it).
    return (path.length === 3 || (path.length === 4 && path[3] === '')) && path[1] === 'video'
      ? match(BILIBILI_ID, path[2], BILIBILI_PLAYER, BILIBILI_SUFFIX) : null
  }
  if (host === 'player.bilibili.com') {
    return path.length === 2 && path[1] === 'player.html'
      ? match(BILIBILI_ID, query('bvid'), BILIBILI_PLAYER, BILIBILI_SUFFIX) : null
  }
  if (NICONICO_HOSTS.has(host)) return path.length === 3 && path[1] === 'watch' ? match(NICONICO_ID, path[2], NICONICO_PLAYER) : null
  if (host === 'nico.ms') return path.length === 2 ? match(NICONICO_ID, path[1], NICONICO_PLAYER) : null
  if (DAILYMOTION_HOSTS.has(host)) {
    const id = path.length === 3 && path[1] === 'video' ? path[2]
      : path.length === 4 && path[1] === 'embed' && path[2] === 'video' ? path[3] : null
    return match(DAILYMOTION_ID, id, DAILYMOTION_PLAYER)
  }
  if (host === 'dai.ly') return path.length === 2 ? match(DAILYMOTION_ID, path[1], DAILYMOTION_PLAYER) : null
  if (host === 'v.youku.com') {
    // v.youku.com/v_show/id_<id>.html
    return path.length === 3 && path[1] === 'v_show' && path[2].startsWith('id_')
      ? match(YOUKU_ID, html(path[2].slice(3)), YOUKU_PLAYER) : null
  }
  if (host === 'player.youku.com') return path.length === 3 && path[1] === 'embed' ? match(YOUKU_ID, path[2], YOUKU_PLAYER) : null
  if (host === 'v.qq.com') {
    // v.qq.com/x/page/<vid>.html, v.qq.com/x/cover/<cover>/<vid>.html, and the player itself.
    if (path.length === 4 && path[1] === 'x' && path[2] === 'page') return match(TENCENT_ID, html(path[3]), TENCENT_PLAYER)
    if (path.length === 5 && path[1] === 'x' && path[2] === 'cover' && TENCENT_COVER.test(path[3])) {
      return match(TENCENT_ID, html(path[4]), TENCENT_PLAYER)
    }
    if (path.length === 4 && path[1] === 'txp' && path[2] === 'iframe' && path[3] === 'player.html') {
      return match(TENCENT_ID, query('vid'), TENCENT_PLAYER)
    }
    return null
  }
  return null
}

/** The video sites as the parameter `qb.video.mainland-accessible-sites` names them (the backend's `VideoEmbeds.Site`). */
export type VideoSite = 'youtube' | 'vimeo' | 'bilibili' | 'niconico' | 'dailymotion' | 'youku' | 'tencent'

const SITE_PLAYERS: [VideoSite, string][] = [['youtube', YOUTUBE_PLAYER], ['vimeo', VIMEO_PLAYER],
  ['bilibili', BILIBILI_PLAYER], ['niconico', NICONICO_PLAYER], ['dailymotion', DAILYMOTION_PLAYER],
  ['youku', YOUKU_PLAYER], ['tencent', TENCENT_PLAYER]]

/** F-S11: the site of a link to one video, as `videoPlayer` reads it; null for any other address. */
export function videoSite(url: string | null | undefined): VideoSite | null {
  const player = videoPlayer(url)
  return player ? SITE_PLAYERS.find(([, prefix]) => player.startsWith(prefix))?.[0] ?? null : null
}

/** The parameter's first value: the sites mainland China plays (the backend's `ForeignVideos.DEFAULT_SITES`). */
export const MAINLAND_SITES_DEFAULT = 'bilibili,youku,tencent'

/** The sites of the parameter's value (unknown keys ignored); the default's when there is none. */
export function mainlandSites(value: string | null | undefined): Set<VideoSite> {
  const known = new Set<string>(SITE_PLAYERS.map(([site]) => site))
  return new Set((value ?? MAINLAND_SITES_DEFAULT).split(',').map((key) => key.trim().toLowerCase())
    .filter((key): key is VideoSite => known.has(key)))
}

/**
 * F-S11: whether any of these video links is foreign, as the backend's `ForeignVideos` decides: a link of a site not
 * in `accessible`, or of no site recognised; a blank link is no video. Only for the hints of the pages: the server
 * freezes the answer on the publication's card and refuses a start from mainland China by it.
 */
export function foreignVideo(links: readonly (string | null | undefined)[], accessible: ReadonlySet<VideoSite>): boolean {
  return links.some((link) => {
    if (!link || !link.trim()) return false
    const site = videoSite(link)
    return !site || !accessible.has(site)
  })
}

/** The video links of a version's snapshot, every language's (the base's materials and each language's own). */
export function snapshotVideoLinks(content: Snapshot): string[] {
  return [...content.materials, ...Object.values(content.translations ?? {}).flatMap((language) => language.materials)]
    .filter((material) => material.kind === 'VIDEO_LINK' && !!material.url).map((material) => material.url as string)
}

/** The regions that reach mainland China (CN is in CN and ASIA; every country is in GLOBAL); an invitation may too. */
export function reachesMainland(audience: string, regions: readonly string[]): boolean {
  return audience === 'INVITED' || regions.some((region) => ['GLOBAL', 'CN', 'ASIA'].includes(region))
}
