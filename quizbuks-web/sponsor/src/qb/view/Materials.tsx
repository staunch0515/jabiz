import { FilePreview, MarkdownView, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, Spinner,
  useFileUrl } from '@jabiz/ui'
import { ChevronRight, ExternalLink, FileText, Headphones, Images, Link2, Newspaper, Video, type LucideIcon }
  from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { QBVIEW } from './messages'
import { CONTENT_IMAGE_VARIANTS } from '../ids'
import { videoPlayer } from '../video'
import { VideoPlayer } from './VideoPlayer'
import type { Material, MaterialKind } from '../play'
import { DetailCard } from './DetailCard'

const IMAGE_FIELD = { image: true, variants: [...CONTENT_IMAGE_VARIANTS] }
const FILE_FIELD = { image: false }

/** The icon of each kind of material in the list (Q34). */
export const MATERIAL_ICONS: Record<MaterialKind, LucideIcon> = {
  ARTICLE: Newspaper,
  LINK: Link2,
  VIDEO_LINK: Video,
  PDF: FileText,
  IMAGES: Images,
  AUDIO: Headphones,
}

/** An audio material, fetched with the session and played from an object URL (released when it goes). */
function Audio({ fileId, title }: { fileId: string; title: string }) {
  const { t } = useTranslation(QBVIEW)
  const { url, failed } = useFileUrl(fileId, undefined)
  if (failed) return <p className="text-muted-foreground text-sm">{t('detail.fileFailed')}</p>
  if (!url) return <Spinner label={t('detail.loadingFile')} />
  return <audio controls preload="metadata" src={url} aria-label={title} className="w-full" />
}

/** A link's address when it is a web page's (http or https); anything else is not followed. */
export function safeLink(url: string | null): string | null {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : null
  } catch {
    return null
  }
}

/** A link of a material: always to another site, opened in a new tab (a video the App cannot show, Q-3, Q19). */
function OutLink({ url, label }: { url: string; label: string }) {
  const { t } = useTranslation(QBVIEW)
  const safe = safeLink(url)
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

/** The one content a material's kind has, as it is read in full (the sheet; an in-page video). */
function MaterialContent({ material, title }: { material: Material; title: string }) {
  const { t } = useTranslation(QBVIEW)
  const m = material
  return (
    <>
      {m.kind === 'ARTICLE' && m.body && <MarkdownView text={m.body} />}
      {m.kind === 'LINK' && m.url && <OutLink url={m.url} label={t('detail.openLink')} />}
      {m.kind === 'VIDEO_LINK' && m.url && (videoPlayer(m.url)
        // Q19: a YouTube or Vimeo video plays in the page; any other video opens on its own site.
        ? <VideoPlayer player={videoPlayer(m.url)!} title={title} />
        : <OutLink url={m.url} label={t('detail.watchVideo')} />)}
      {m.kind === 'PDF' && m.pdf && <FilePreview fileId={m.pdf} field={FILE_FIELD} width={0} />}
      {m.kind === 'AUDIO' && m.audio && <Audio fileId={m.audio} title={title} />}
      {m.kind === 'IMAGES' && m.images.length > 0 && (
        <div className="grid grid-cols-2 gap-2">
          {m.images.map((image) => (
            <figure key={image.no} className="flex flex-col gap-1">
              <FilePreview fileId={image.image} field={IMAGE_FIELD} width={180} />
              {image.caption && <figcaption className="text-muted-foreground text-xs">{image.caption}</figcaption>}
            </figure>
          ))}
        </div>
      )}
    </>
  )
}

/** Whether a material has something to read in full; one without (an empty article) is only its row. */
function hasContent(m: Material): boolean {
  switch (m.kind) {
    case 'ARTICLE': return !!m.body
    case 'LINK': case 'VIDEO_LINK': return !!safeLink(m.url)
    case 'PDF': return !!m.pdf
    case 'AUDIO': return !!m.audio
    case 'IMAGES': return m.images.length > 0
    default: return false
  }
}

/**
 * The video a taker learns from before answering (Q63), as the card's (`PublicationEditing.cardVideo`): the one watching
 * which is enough to answer, else the first video the App plays; with its player.
 */
export function leadVideo(materials: Material[]): { material: Material; player: string } | null {
  const playerOf = (m: Material) => (m.kind === 'VIDEO_LINK' ? videoPlayer(m.url) : null)
  const material = materials.find((m) => m.watchToAnswer && playerOf(m)) ?? materials.find((m) => playerOf(m))
  return material ? { material, player: playerOf(material)! } : null
}

/**
 * The reference materials of the publication's version (U-21; Q34): a video the app can play first, in the page; every
 * other one a row with its kind's icon, its title and its description on one line. A row opens the material in full
 * on a sheet over the whole screen (a link goes straight to its site in a new tab), so the page stays short. Markdown
 * without raw HTML (`MarkdownView`); files fetched with the session.
 */
export function Materials({ materials: all, exclude }: { materials: Material[]
  /** Q63: the material already shown at the top of the page (its video), left out here. */
  exclude?: number }) {
  const materials = exclude === undefined ? all : all.filter((m) => m.no !== exclude)
  const { t } = useTranslation(QBVIEW)
  // The material shown stays while the sheet slides away, so it does not empty before it is gone.
  const [shown, setShown] = useState<Material | null>(null)
  const [sheetOpen, setSheetOpen] = useState(false)
  const show = (m: Material) => {
    setShown(m)
    setSheetOpen(true)
  }
  if (materials.length === 0) return null
  const kindName = (m: Material) => t(`detail.kinds.${m.kind}`, { defaultValue: t('detail.material') })
  const titleOf = (m: Material) => m.title ?? kindName(m)
  const playable = (m: Material) => m.kind === 'VIDEO_LINK' && !!m.url && !!videoPlayer(m.url)
  const videos = materials.filter(playable)
  const rows = materials.filter((m) => !playable(m))
  return (
    <DetailCard title={t('detail.materials')} titleId="materials-title" testId="materials" flush>
      {videos.map((m) => (
        <div key={m.no} className="flex flex-col gap-1.5 border-b p-3 last:border-b-0" data-testid="material"
          data-kind={m.kind}>
          <MaterialContent material={m} title={titleOf(m)} />
          <p className="text-sm font-medium">{titleOf(m)}</p>
          {m.description && <p className="text-muted-foreground text-xs whitespace-pre-line">{m.description}</p>}
        </div>
      ))}
      {rows.length > 0 && (
        <ul className="divide-y">
          {rows.map((m) => {
            const Icon = MATERIAL_ICONS[m.kind] ?? FileText
            const title = titleOf(m)
            // A link without more to say goes straight to its site; one with a description opens the sheet first, so
            // the description can be read in full there (the row shows one line of it).
            const link = (m.kind === 'LINK' || m.kind === 'VIDEO_LINK') && !m.description ? safeLink(m.url) : null
            const inner = (
              <>
                <span aria-hidden className="bg-accent text-accent-foreground flex size-9 shrink-0 items-center justify-center">
                  <Icon className="size-4" />
                </span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-sm font-medium" data-testid="material-title">{title}</span>
                  {m.description && (
                    <span className="text-muted-foreground truncate text-xs" data-testid="material-description">
                      {m.description}
                    </span>
                  )}
                </span>
                {link
                  ? <ExternalLink aria-hidden className="text-muted-foreground size-4 shrink-0" />
                  : <ChevronRight aria-hidden className="text-muted-foreground size-4 shrink-0" />}
              </>
            )
            const row = 'hover:bg-accent/50 focus-visible:ring-ring/50 flex w-full items-center gap-3 px-3 py-2.5 text-left outline-none focus-visible:ring-[3px]'
            return (
              <li key={m.no} data-testid="material" data-kind={m.kind}>
                {link ? (
                  <a href={link} target="_blank" rel="noopener noreferrer" className={row} data-testid="material-link">
                    {inner}
                    <span className="sr-only">{t('detail.newTab')}</span>
                  </a>
                ) : hasContent(m) || m.description ? (
                  <button type="button" className={row} onClick={() => show(m)} data-testid="material-open"
                    aria-haspopup="dialog">
                    {inner}
                  </button>
                ) : (
                  <div className={row}>{inner}</div>
                )}
              </li>
            )
          })}
        </ul>
      )}
      <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
        {/* The header with the close button stays; only the content under it scrolls (a long article or PDF). */}
        <SheetContent side="bottom" className="mx-auto flex h-[100dvh] max-w-md flex-col gap-0 overflow-hidden"
          data-testid="material-sheet">
          {shown && (
            <>
              <SheetHeader className="bg-muted shrink-0 border-b pr-12">
                <SheetTitle>{titleOf(shown)}</SheetTitle>
                {shown.description
                  ? <SheetDescription className="whitespace-pre-line">{shown.description}</SheetDescription>
                  : <SheetDescription className="sr-only">{kindName(shown)}</SheetDescription>}
              </SheetHeader>
              <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4" data-testid="material-content">
                <MaterialContent material={shown} title={titleOf(shown)} />
              </div>
            </>
          )}
        </SheetContent>
      </Sheet>
    </DetailCard>
  )
}
