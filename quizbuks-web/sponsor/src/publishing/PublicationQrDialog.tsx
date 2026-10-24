import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  notify,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { Download, FileImage } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { download } from '../content/transfer'
import { LANGUAGES } from '../qb/messageKeys'
import { shareLink } from './paths'
import { posterPng, qrFileName, qrImageUrl, qrPng, qrSvg } from './qr'

/**
 * The QR code of a publication's share link (Q21): shown with the link, saved as PNG or SVG for the sponsor's own
 * design, or as a ready poster (A4) to print and post at the stops nearby.
 */
export function PublicationQrDialog({ publicationId, name, open, onOpenChange }: {
  publicationId: string
  name: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const link = shareLink(publicationId)
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
        <div className="flex flex-col items-center gap-3">
          {image.data ? (
            // The image carries its own light background: it stays scannable in the dark appearance.
            <img src={image.data} alt={t('publishing.qr.alt', { name })} width={240} height={240}
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
            onClick={() => void save('png', () => qrPng(link), qrFileName(name, 'QR', 'png'))}>
            <Download aria-hidden />
            {t('publishing.qr.png')}
          </Button>
          <Button type="button" variant="outline" disabled={saving !== null} aria-busy={saving === 'svg' || undefined}
            data-testid="publication-qr-svg"
            onClick={() => void save('svg', () => qrSvg(link), qrFileName(name, 'QR', 'svg'))}>
            <Download aria-hidden />
            {t('publishing.qr.svg')}
          </Button>
          <Button type="button" disabled={saving !== null} aria-busy={saving === 'poster' || undefined}
            data-testid="publication-qr-poster"
            onClick={() => void save('poster', () => posterPng(link, { name, instructions }),
              qrFileName(name, 'poster', 'png'))}>
            <FileImage aria-hidden />
            {t('publishing.qr.poster')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
