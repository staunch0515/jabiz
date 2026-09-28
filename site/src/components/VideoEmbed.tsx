import { useEffect, useId, useRef, useState } from 'react'
import type { LocalizedText } from '../api/public-queries'
import { useLocale, useT } from '../i18n/locale'
import { embedUrl, providerName } from '../lib/video'
import { Markdown } from './Markdown'
import { ResponsiveImage } from './ResponsiveImage'
import styles from './VideoEmbed.module.css'

interface Props {
  provider: string | null | undefined
  videoId: string | null | undefined
  /** What the video is, for the play button and the player's frame title. */
  title: string
  /** Our own poster (a story's thumbnail or a participant's portrait); nothing is fetched from the player's host. */
  posterFileId?: string | null
  transcript?: LocalizedText | null
}

/**
 * A YouTube or Vimeo video that loads only when asked to (design section 9.3): until the button is pressed the page
 * shows our own poster and makes no request to the player's host (privacy and performance). The player starts with
 * subtitles on, in the interface language; the transcript is always available below.
 */
export function VideoEmbed({ provider, videoId, title, posterFileId, transcript }: Props) {
  const t = useT()
  const locale = useLocale()
  const [playing, setPlaying] = useState(false)
  const frame = useRef<HTMLIFrameElement>(null)
  const noticeId = useId()
  const src = embedUrl(provider, videoId, locale)

  useEffect(() => {
    // The button the keyboard was on is gone: the player takes its place, and the focus.
    if (playing) frame.current?.focus()
  }, [playing])

  if (!src) return null
  const host = providerName(provider)
  return (
    <div>
      <div className={styles.video}>
        {playing ? (
          <iframe
            ref={frame}
            className={styles.frame}
            src={src}
            title={t('video.frameTitle', { title })}
            allow="autoplay; encrypted-media; fullscreen; picture-in-picture"
            allowFullScreen
            referrerPolicy="strict-origin-when-cross-origin"
          />
        ) : (
          <button type="button" className={styles.play} onClick={() => setPlaying(true)}
            aria-label={t('video.play', { title })}
            aria-describedby={noticeId}
          >
            {posterFileId ? (
              <span className={styles.poster}>
                <ResponsiveImage fileId={posterFileId} alt="" ratio="wide" sizes="(max-width: 860px) 100vw, 66vw" />
              </span>
            ) : null}
            <span className={styles.disc} aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M6 4l14 8-14 8z" />
              </svg>
            </span>
            <span className={styles.notice} id={noticeId}>
              {t('video.notice', { provider: host })}
            </span>
          </button>
        )}
      </div>
      {transcript ? <Transcript value={transcript} /> : null}
    </div>
  )
}

export function Transcript({ value }: { value: LocalizedText }) {
  const t = useT()
  return (
    <details className={styles.transcript}>
      <summary>{t('story.transcript')}</summary>
      <Markdown value={value} />
    </details>
  )
}
