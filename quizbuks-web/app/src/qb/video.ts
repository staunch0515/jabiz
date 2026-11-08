/**
 * The video players the App shows (Q19, Q99; docs/quizbuks/plans/Q19-embedded-video.md, Q99-more-video-sites.md), as
 * the backend's `content.VideoEmbeds` reads links: one video of YouTube (its no-cookie player), Vimeo, Bilibili,
 * Niconico, Dailymotion, Youku or Tencent Video as its site's player. The App's content security policy frames these
 * players only (`frame-src`); any other link stays a link. Every site's video plays the same way, on the home and the
 * start page (Q99b); only YouTube's and Vimeo's players say where they are (`trackable`), so only they can be the video
 * to watch before answering (Q75) and take the sound from the app's tap (the others' sound is their own button).
 *
 * F-S15 (docs/quizbuks/plans/impl/F-S15.md): a video link may be on any https address. A video file (`.mp4`, `.webm`,
 * `.mov` on any host but the sites': `filePlayer`) plays in the page's own `<video>` (`media-src https:`), which says
 * where it is (`trackable`); any other https address (`pagePlayer`) is a player page framed in a sandbox
 * (`frame-src https:`, `PAGE_SANDBOX`). Read as text, not through `URL` (which resolves dot segments), as the backend's
 * `VideoEmbeds` reads them: the same cases.
 */
export const YOUTUBE_PLAYER = 'https://www.youtube-nocookie.com/embed/'
export const VIMEO_PLAYER = 'https://player.vimeo.com/video/'
export const BILIBILI_PLAYER = 'https://player.bilibili.com/player.html?bvid='
/** Bilibili's player starts by itself unless told not to. */
const BILIBILI_SUFFIX = '&autoplay=0'
export const NICONICO_PLAYER = 'https://embed.nicovideo.jp/watch/'
export const DAILYMOTION_PLAYER = 'https://www.dailymotion.com/embed/video/'
export const YOUKU_PLAYER = 'https://player.youku.com/embed/'
export const TENCENT_PLAYER = 'https://v.qq.com/txp/iframe/player.html?vid='

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

/** F-S15: an https address as written: a host, plain path segments, an optional query and fragment; no user, no port. */
const CHARS = "(?:[A-Za-z0-9._~!$&'()*+,;=:@-]|%[0-9A-Fa-f]{2})"
const ADDRESS = new RegExp(`^[Hh][Tt][Tt][Pp][Ss]://([A-Za-z0-9.-]+)((?:/${CHARS}+)*/?)(\\?(?:${CHARS}|[/?])*)?(#(?:${CHARS}|[/?])*)?$`)
const HOST = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$/
/** F-S15: the endings of a video file's path played with `<video>`. */
export const FILE_EXTENSIONS = ['.mp4', '.webm', '.mov'] as const
/** The seven sites' hosts: an address there that is not one video is no video (a channel, a short link). */
const SITE_HOSTS = new Set([...YOUTUBE_HOSTS, ...VIMEO_HOSTS, ...BILIBILI_HOSTS, ...NICONICO_HOSTS, ...DAILYMOTION_HOSTS,
  'youtu.be', 'player.bilibili.com', 'b23.tv', 'www.b23.tv', 'nico.ms', 'dai.ly', 'geo.dailymotion.com', 'v.youku.com',
  'player.youku.com', 'youku.com', 'www.youku.com', 'v.qq.com'])

function address(url: string | null | undefined): RegExpExecArray | null {
  if (!url) return null
  const found = ADDRESS.exec(url.trim())
  if (!found || !HOST.test(found[1].toLowerCase())) return null
  // Dot segments: a browser resolves them, so the address played would differ; none is taken.
  if (found[2].split('/').some((segment) => segment === '.' || segment === '..')) return null
  return found
}

const normal = (found: RegExpExecArray) => `https://${found[1].toLowerCase()}${found[2]}${found[3] ?? ''}${found[4] ?? ''}`

/**
 * F-S15: a video file on any host but the sites': an https address whose path ends with `.mp4`, `.webm` or `.mov` (the
 * query and fragment do not count); the address played is the link with its scheme and host in lower case.
 */
export function filePlayer(url: string | null | undefined): string | null {
  const found = address(url)
  if (!found || SITE_HOSTS.has(found[1].toLowerCase())) return null
  const last = found[2].slice(found[2].lastIndexOf('/') + 1).toLowerCase()
  return FILE_EXTENSIONS.some((ending) => last.length > ending.length && last.endsWith(ending)) ? normal(found) : null
}

/** F-S15: any other https page, framed in a sandbox: not the sites' hosts, not a video file. */
export function pagePlayer(url: string | null | undefined): string | null {
  const found = address(url)
  if (!found || SITE_HOSTS.has(found[1].toLowerCase()) || filePlayer(url)) return null
  return normal(found)
}

/** F-S15: the host of a video file's or a page's link (lower case), for F-S11's hosts. */
export function videoHost(url: string | null | undefined): string | null {
  const found = address(url)
  return found ? found[1].toLowerCase() : null
}

/** F-S15: the three kinds of player. */
export type VideoKind = 'site' | 'file' | 'page'

/** The kind of a player as `videoPlayer` gives it; null for any other address. */
export function videoKind(player: string | null | undefined): VideoKind | null {
  if (!player) return null
  if (sitePlayer(player) === player) return 'site'
  if (filePlayer(player) === player) return 'file'
  return pagePlayer(player) === player ? 'page' : null
}

/**
 * F-S15: the frame of a page of another site: its scripts in its own origin (its player keeps its storage), no
 * navigation of the app, no pop-ups, no forms; no referrer.
 */
export const PAGE_SANDBOX = 'allow-scripts allow-same-origin allow-presentation'
export const PAGE_ALLOW = 'autoplay; fullscreen; picture-in-picture'

/**
 * Whether a player (as `videoPlayer` gives it) says where it is (Q75): YouTube's and Vimeo's. The others play, with
 * their own play button, but cannot be the video to watch before answering.
 */
export function trackable(player: string | null | undefined): boolean {
  return !!player && (player.startsWith(YOUTUBE_PLAYER) || player.startsWith(VIMEO_PLAYER) || videoKind(player) === 'file')
}

/**
 * F-S10: whether a video link is a YouTube Shorts link (`youtube.com/shorts/<id>`), read as `videoPlayer` reads links:
 * such a video is vertical whatever its material says (the backend's `VideoEmbeds.shorts`, the same cases).
 */
export function isShorts(url: string | null | undefined): boolean {
  if (!sitePlayer(url)) return false
  const parsed = new URL(url!.trim())
  const path = parsed.pathname.split('/')
  return YOUTUBE_HOSTS.has(parsed.hostname.toLowerCase()) && path.length === 3 && path[1] === 'shorts'
}

/** F-S10: whether a video material is vertical: a YouTube Shorts link, or marked so by the sponsor. */
export function verticalVideo(url: string | null | undefined, marked: boolean | null | undefined): boolean {
  return !!marked || isShorts(url)
}

/**
 * The player's address for a link to any video the app plays (F-S15): the seven sites' player, else a video file, else
 * another https page; null for anything else.
 */
export function videoPlayer(url: string | null | undefined): string | null {
  return sitePlayer(url) ?? filePlayer(url) ?? pagePlayer(url)
}

/** The player's address for a link to one video of the seven sites above; null for any other address. */
export function sitePlayer(url: string | null | undefined): string | null {
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

/**
 * The player started muted at once (Q63: the home's card in the middle of the screen, the start page): browsers let
 * a page start only a silent video by itself; the viewer turns the sound on in the player. Every site the same way
 * (Q99b), each with its own parameters; a site without them (Youku, Tencent Video) shows its play button instead.
 */
export function autoplaying(player: string): string {
  if (player.startsWith(VIMEO_PLAYER)) return `${player}?autoplay=1&muted=1&playsinline=1`
  if (player.startsWith(YOUTUBE_PLAYER)) return `${player}?autoplay=1&mute=1&playsinline=1`
  if (player.startsWith(BILIBILI_PLAYER)) return `${player.replace(BILIBILI_SUFFIX, '')}&autoplay=1&muted=1`
  if (player.startsWith(DAILYMOTION_PLAYER)) return `${player}?autoplay=1&mute=1`
  if (player.startsWith(NICONICO_PLAYER)) return `${player}?autoplay=1&mute=1`
  return player
}

/** Whether the viewer asked for less motion: then no video starts by itself (Q63). */
export function prefersStillVideo(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** A player address the server gave (the card's): only one of the players above (F-S15: a file, a page), else none. */
export function trustedPlayer(address: string | null | undefined): string | null {
  if (!address) return null
  return videoPlayer(address) === address ? address : null
}

/** The video sites as the parameter `qb.video.mainland-accessible-sites` names them (the backend's `VideoEmbeds.Site`). */
export type VideoSite = 'youtube' | 'vimeo' | 'bilibili' | 'niconico' | 'dailymotion' | 'youku' | 'tencent'

const SITE_PLAYERS: [VideoSite, string][] = [['youtube', YOUTUBE_PLAYER], ['vimeo', VIMEO_PLAYER],
  ['bilibili', BILIBILI_PLAYER], ['niconico', NICONICO_PLAYER], ['dailymotion', DAILYMOTION_PLAYER],
  ['youku', YOUKU_PLAYER], ['tencent', TENCENT_PLAYER]]

/** F-S11: the site of a link to one video, as `videoPlayer` reads it; null for any other address. */
export function videoSite(url: string | null | undefined): VideoSite | null {
  const player = sitePlayer(url)
  return player ? SITE_PLAYERS.find(([, prefix]) => player.startsWith(prefix))?.[0] ?? null : null
}

/** The parameter's first value: the sites mainland China plays (the backend's `ForeignVideos.DEFAULT_SITES`). */
export const MAINLAND_SITES_DEFAULT = 'bilibili,youku,tencent'

/** What mainland China plays (F-S11, F-S15): the sites the parameter names, and the hosts it names. */
export interface MainlandAccessible {
  sites: ReadonlySet<VideoSite>
  hosts: ReadonlySet<string>
}

/** The sites and host names of the parameter's value (anything else ignored); the default's when there is none. */
export function mainlandSites(value: string | null | undefined): MainlandAccessible {
  const known = new Set<string>(SITE_PLAYERS.map(([site]) => site))
  const sites = new Set<VideoSite>()
  const hosts = new Set<string>()
  for (const key of (value ?? MAINLAND_SITES_DEFAULT).split(',').map((entry) => entry.trim().toLowerCase())) {
    if (known.has(key)) sites.add(key as VideoSite)
    else if (HOST.test(key)) hosts.add(key)
  }
  return { sites, hosts }
}

/** Whether a video link plays in mainland China: its site is listed, or (a file, a page) its host. */
function playsInMainland(link: string, accessible: MainlandAccessible): boolean {
  const site = videoSite(link)
  if (site) return accessible.sites.has(site)
  if (sitePlayer(link) || !videoPlayer(link)) return false
  const host = videoHost(link)
  return !!host && accessible.hosts.has(host)
}

/**
 * F-S11: whether any of these video links is foreign, as the backend's `ForeignVideos` decides: a link of a site not
 * listed, a file or page whose host is not listed (F-S15), or a link of nothing recognised; a blank link is no video.
 * Only for the hints of the pages: the server freezes the answer on the publication's card and refuses a start from
 * mainland China by it.
 */
export function foreignVideo(links: readonly (string | null | undefined)[], accessible: MainlandAccessible): boolean {
  return links.some((link) => !!link && !!link.trim() && !playsInMainland(link, accessible))
}
