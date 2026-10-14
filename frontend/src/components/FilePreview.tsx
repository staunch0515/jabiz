import { previewVariant } from '@jabiz/client'
import { Button, Dialog, DialogContent, DialogHeader, DialogTitle, Spinner } from '@jabiz/ui'
import { Maximize2, Paperclip } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { downloadFile, useFileUrl } from './fileContent'
import type { FileFieldMeta } from '../meta/types'

interface Props {
  fileId: string
  field: FileFieldMeta
  /** Width the preview is shown at; the narrowest variant at least this wide is loaded. */
  width: number
}

/** The width an enlarged image is loaded for. */
const ENLARGED_WIDTH = 1024

/** The enlarged image, loaded while the dialog is open and released when it closes. */
function Enlarged({ fileId, field }: { fileId: string; field: FileFieldMeta }) {
  const { t } = useTranslation()
  const { url, failed } = useFileUrl(fileId, previewVariant(field.variants, ENLARGED_WIDTH))
  if (failed) return <p className="text-muted-foreground text-sm">{t('file.previewFailed')}</p>
  if (!url) return <Spinner label={t('app.loading')} showLabel />
  return <img src={url} alt={t('file.preview')} className="max-h-[75vh] w-full object-contain" />
}

/**
 * An image as a thumbnail that opens larger in a dialog, anything else as a download button. The content is fetched
 * with the session and shown through an object URL that is released when the preview goes (14 section 5).
 */
export default function FilePreview({ fileId, field, width }: Props) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const isImage = field.image === true
  const { url, failed } = useFileUrl(isImage ? fileId : undefined, isImage ? previewVariant(field.variants, width) : undefined)
  if (!isImage) {
    return (
      <Button
        variant="outline"
        size="sm"
        onClick={(event) => {
          event.stopPropagation()
          void downloadFile(fileId)
        }}
      >
        <Paperclip aria-hidden />
        {t('file.download')}
      </Button>
    )
  }
  if (failed) return <span className="text-muted-foreground text-sm">{t('file.previewFailed')}</span>
  if (!url) return <span className="text-muted-foreground text-sm">{t('app.loading')}</span>
  return (
    <>
      {/* The image is not inside the button: a button's content is not exposed as an image. */}
      <span className="inline-flex items-end gap-1">
        <img
          src={url}
          width={width}
          alt={t('file.preview')}
          className="cursor-zoom-in rounded-sm"
          onClick={(event) => {
            event.stopPropagation()
            setOpen(true)
          }}
        />
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={t('file.enlarge')}
          onClick={(event) => {
            event.stopPropagation()
            setOpen(true)
          }}
        >
          <Maximize2 aria-hidden />
        </Button>
      </span>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-3xl" aria-describedby={undefined}>
          <DialogHeader>
            <DialogTitle>{t('file.preview')}</DialogTitle>
          </DialogHeader>
          {open && <Enlarged fileId={fileId} field={field} />}
        </DialogContent>
      </Dialog>
    </>
  )
}
