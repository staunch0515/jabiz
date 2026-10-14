import { FilePreview, MarkdownView, Spinner, useFileUrl } from '@jabiz/ui'
import { ExternalLink } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { CONTENT_IMAGE_VARIANTS } from '../qb/ids'
import type { Material } from '../qb/play'

const IMAGE_FIELD = { image: true, variants: [...CONTENT_IMAGE_VARIANTS] }
const FILE_FIELD = { image: false }

/** An audio material, fetched with the session and played from an object URL (released when it goes). */
function Audio({ fileId, title }: { fileId: string; title: string }) {
  const { t } = useTranslation(APP)
  const { url, failed } = useFileUrl(fileId, undefined)
  if (failed) return <p className="text-muted-foreground text-sm">{t('detail.fileFailed')}</p>
  if (!url) return <Spinner label={t('detail.loadingFile')} />
  return <audio controls preload="metadata" src={url} aria-label={title} className="w-full" />
}

/** A link of a material: always to another site, opened in a new tab (Q-3: videos are links, never embedded). */
function OutLink({ url, label }: { url: string; label: string }) {
  const { t } = useTranslation(APP)
  let safe: string | null = null
  try {
    const parsed = new URL(url)
    if (parsed.protocol === 'https:' || parsed.protocol === 'http:') safe = parsed.href
  } catch {
    safe = null
  }
  if (!safe) return null
  return (
    <a href={safe} target="_blank" rel="noopener noreferrer" data-testid="material-link"
      className="text-primary inline-flex items-center gap-1 text-sm font-medium underline underline-offset-4">
      {label}
      <ExternalLink aria-hidden className="size-3.5" />
      <span className="sr-only">{t('detail.newTab')}</span>
    </a>
  )
}

/**
 * The reference materials of the publication's version (U-21), under its title: each with its title and description
 * and the one content its kind has. Markdown without raw HTML (`MarkdownView`); files fetched with the session.
 */
export function Materials({ materials }: { materials: Material[] }) {
  const { t } = useTranslation(APP)
  if (materials.length === 0) return null
  return (
    <section aria-labelledby="materials-title" className="flex flex-col gap-3" data-testid="materials">
      <h2 id="materials-title" className="text-lg font-semibold">{t('detail.materials')}</h2>
      <ul className="flex flex-col gap-3">
        {materials.map((m) => {
          const title = m.title ?? t(`detail.kinds.${m.kind}`, { defaultValue: t('detail.material') })
          return (
            <li key={m.no} className="flex flex-col gap-2 rounded-lg border p-3" data-testid="material">
              <h3 className="font-medium">{title}</h3>
              {m.description && <p className="text-muted-foreground text-sm whitespace-pre-line">{m.description}</p>}
              {m.kind === 'ARTICLE' && m.body && <MarkdownView text={m.body} />}
              {(m.kind === 'LINK' || m.kind === 'VIDEO_LINK') && m.url && (
                <OutLink url={m.url} label={m.kind === 'VIDEO_LINK' ? t('detail.watchVideo') : t('detail.openLink')} />
              )}
              {m.kind === 'PDF' && m.pdf && <FilePreview fileId={m.pdf} field={FILE_FIELD} width={0} />}
              {m.kind === 'AUDIO' && m.audio && <Audio fileId={m.audio} title={title} />}
              {m.kind === 'IMAGES' && m.images.length > 0 && (
                <div className="grid grid-cols-2 gap-2">
                  {m.images.map((image) => (
                    <figure key={image.no} className="flex flex-col gap-1">
                      <FilePreview fileId={image.image} field={IMAGE_FIELD} width={180} />
                      {image.caption && (
                        <figcaption className="text-muted-foreground text-xs">{image.caption}</figcaption>
                      )}
                    </figure>
                  ))}
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
