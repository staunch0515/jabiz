import { useEffect, useRef } from 'react'
import { VIMEO_PLAYER, videoKind } from '../qb/video'

/**
 * A preview of the video to watch that reads its duration from the player (Q75, D-Q75-5): YouTube's player tells it
 * in its `infoDelivery` once a page says it listens (`enablejsapi`), Vimeo's answers `getDuration`. Only these two
 * players are framed (the console's content security policy), and only a message from this frame is heard.
 *
 * F-S15: a video file is previewed in a `<video>` that reads its length from the file's metadata (no CORS needed);
 * a file that fails, or whose length cannot be read, is `onFailed`.
 */
export function VideoDurationProbe({ player, title, onDuration, onFailed }: { player: string; title: string
  onDuration: (seconds: number) => void
  onFailed?: () => void }) {
  if (videoKind(player) === 'file') {
    return (
      <video key={player} src={player} title={title} aria-label={title} data-testid="material-video-probe"
        className="bg-muted aspect-video w-full max-w-xs rounded-md" controls muted playsInline preload="metadata"
        onLoadedMetadata={(event) => {
          const seconds = event.currentTarget.duration
          if (Number.isFinite(seconds) && seconds > 0) onDuration(Math.round(seconds))
          else onFailed?.()
        }}
        onError={() => onFailed?.()} />
    )
  }
  return <FrameProbe player={player} title={title} onDuration={onDuration} />
}

function FrameProbe({ player, title, onDuration }: { player: string; title: string
  onDuration: (seconds: number) => void }) {
  const ref = useRef<HTMLIFrameElement>(null)
  const vimeo = player.startsWith(VIMEO_PLAYER)
  useEffect(() => {
    const frame = ref.current
    if (!frame) return
    const listen = (event: MessageEvent) => {
      if (event.source !== frame.contentWindow) return
      let data: Record<string, unknown>
      try {
        data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data
      } catch {
        return
      }
      const info = data?.info as Record<string, unknown> | undefined
      const payload = data?.data as Record<string, unknown> | undefined
      const duration = typeof info?.duration === 'number' ? info.duration
        : data?.method === 'getDuration' && typeof data.value === 'number' ? data.value
          : typeof payload?.duration === 'number' ? payload.duration : null
      if (duration != null && duration > 0) onDuration(Math.round(duration))
    }
    window.addEventListener('message', listen)
    return () => window.removeEventListener('message', listen)
  }, [onDuration])
  const greet = () => {
    const target = ref.current?.contentWindow
    if (!target) return
    if (vimeo) target.postMessage(JSON.stringify({ method: 'getDuration' }), 'https://player.vimeo.com')
    else target.postMessage(JSON.stringify({ event: 'listening', id: 'qb-probe', channel: 'widget' }), 'https://www.youtube-nocookie.com')
  }
  const src = vimeo ? `${player}?api=1`
    : `${player}?enablejsapi=1&origin=${encodeURIComponent(window.location.origin)}`
  return (
    <iframe ref={ref} src={src} title={title} onLoad={greet} data-testid="material-video-probe"
      className="bg-muted aspect-video w-full max-w-xs rounded-md border-0"
      allow="encrypted-media"
      referrerPolicy="strict-origin-when-cross-origin"
      sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox" />
  )
}
