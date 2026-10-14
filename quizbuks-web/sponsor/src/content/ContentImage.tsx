import { api, previewVariant, unwrap } from '@jabiz/client'
import { cn, FilePreview, Spinner, useFileUrl } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'

/**
 * The variants a content image really has. The policy names three widths, but the server makes none wider than the
 * original (a small logo has none), so the file's own list decides which one to load.
 */
function useVariants(fileId: string) {
  const info = useQuery({
    queryKey: ['qb', 'file', fileId],
    queryFn: () => unwrap(api.GET('/api/files/{fileId}', { params: { path: { fileId } } })),
    staleTime: Infinity,
  })
  return { pending: info.isPending, variants: info.data?.variants ?? [] }
}

/** An image of the quiz as a thumbnail that opens larger (`FilePreview`), at the narrowest variant it has. */
export function ContentImage({ fileId, width }: { fileId: string; width: number }) {
  const { t } = useTranslation(SPONSOR)
  const { pending, variants } = useVariants(fileId)
  if (pending) return <Spinner label={t('editor.loadingImage')} />
  return <FilePreview fileId={fileId} field={{ image: true, variants }} width={width} />
}

/** A small decorative picture (a cover in a list): nothing while loading or when it cannot be read. */
export function Thumbnail({ fileId, width, className }: { fileId: string; width: number; className?: string }) {
  const { pending, variants } = useVariants(fileId)
  const { url } = useFileUrl(pending ? undefined : fileId, previewVariant(variants, width))
  if (!url) return <span aria-hidden className={cn('bg-muted inline-block', className)} />
  return <img src={url} alt="" className={cn('object-cover', className)} />
}
