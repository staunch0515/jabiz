import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  notify,
  SegmentedControl,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { Download, FileImage } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import type { ContentLanguage } from '../qb/api'
import { download } from '../content/transfer'
import { LANGUAGES } from '../qb/messageKeys'
import { shareLink } from './paths'
import { posterPng, qrFileName, qrImageUrl, qrPng, qrSvg } from './qr'

/**
 * The QR code of a publication's share link (Q21): shown with the link, saved as PNG or SVG for the sponsor's own
 * design, or as a ready poster (A4) to print and post at the stops nearby.
 */
export function PublicationQrDialog({ publicationId, language, languages = [], names = {}, name, open,
  onOpenChange }: {
  publicationId: string
  /** The language the link carries (Q59b): the publication's main one (Q73b), else the quiz's. */
  language: string | null
  /** Q73b: the languages the publication offers, to choose the link's from, and its name in each but the base. */
  languages?: ContentLanguage[]
  names?: Partial<Record<string, string>>
  name: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [chosen, setChosen] = useState<string | null>(null)
  const shown = chosen ?? language
  const link = shareLink(publicationId, shown)
  // The poster names it in the link's language when the publication has the name in it.
  const shownName = (shown && names[shown]) || name
  const image = useQuery({ queryKey: ['publication-qr', link], queryFn: () => qrImageUrl(link), staleTime: Infinity })
  const [saving, setSaving] = useState<string | null>(null)

  const save = async (what: string, make: () => Promise<Blob>, name: string) => {
    setSaving(what)
    try {
      const blob = await make()
      download({ blob, name, size: blob.size })
    } catch {
      notify.error(t('publishing.qr.failed'))
    } finally {
      setSaving(null)
    }
  }
  // The poster's instruction in every language the App speaks: a stop's passers-by are not all alike.
  const instructions = LANGUAGES.map((lng) => t('publishing.qr.posterLine', { lng }))

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent data-testid="publication-qr-dialog">
        <DialogHeader>
          <DialogTitle>{t('publishing.qr.title')}</DialogTitle>
          <DialogDescription>{t('publishing.qr.text')}</DialogDescription>
        </DialogHeader>
        {languages.length > 1 && (
          <div className="flex flex-col items-center gap-1">
            <SegmentedControl<string> value={shown ?? languages[0]} onChange={setChosen}
              aria-label={t('publishing.qr.language')} data-testid="publication-qr-language"
              options={languages.map((l) => ({ value: l, label: tQb(`languages.${l}`) }))} />
            <p className="text-muted-foreground text-xs">{t('publishing.qr.languageHint')}</p>
          </div>
        )}
        <div className="flex flex-col items-center gap-3">
          {image.data ? (
            // The image carries its own light background: it stays scannable in the dark appearance.
            <img src={image.data} alt={t('publishing.qr.alt', { name: shownName })} width={240} height={240}
              className="rounded-md" data-testid="publication-qr-image" />
          ) : image.isError ? (
            <p role="alert" className="text-destructive text-sm" data-testid="publication-qr-failed">
              {t('publishing.qr.failed')}
            </p>
          ) : (
            <div className="bg-muted size-[240px] animate-pulse rounded-md" aria-hidden />
          )}
          <p className="text-muted-foreground text-center text-sm break-all" data-testid="publication-qr-link">{link}</p>
        </div>
        <DialogFooter className="flex-wrap">
          <Button type="button" variant="outline" disabled={saving !== null} aria-busy={saving === 'png' || undefined}
            data-testid="publication-qr-png"
            onClick={() => void save('png', () => qrPng(link), qrFileName(shownName, 'QR', 'png'))}>
            <Download aria-hidden />
            {t('publishing.qr.png')}
          </Button>
          <Button type="button" variant="outline" disabled={saving !== null} aria-busy={saving === 'svg' || undefined}
            data-testid="publication-qr-svg"
            onClick={() => void save('svg', () => qrSvg(link), qrFileName(shownName, 'QR', 'svg'))}>
            <Download aria-hidden />
            {t('publishing.qr.svg')}
          </Button>
          <Button type="button" disabled={saving !== null} aria-busy={saving === 'poster' || undefined}
            data-testid="publication-qr-poster"
            onClick={() => void save('poster', () => posterPng(link, { name: shownName, instructions }),
              qrFileName(shownName, 'poster', 'png'))}>
            <FileImage aria-hidden />
            {t('publishing.qr.poster')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
