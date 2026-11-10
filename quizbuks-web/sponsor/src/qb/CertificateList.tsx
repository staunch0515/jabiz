import { formatDate, i18n } from '@jabiz/client'
import { Badge, Button, Label, notify, PageState, Switch } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Award, Copy, Download } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import {
  canDownload,
  certificateTitle,
  downloadCertificate,
  isRevoked,
  readCertificates,
  setShared,
  SOURCE_NODE,
  type Certificate,
  type CertificateCalls,
} from './certificates'
import { QB } from './messages'
import { problemText } from './problems'

/** Copies a text, or says it could not (the text stays on screen to copy by hand). */
async function copy(text: string, done: string, failed: string) {
  try {
    await navigator.clipboard.writeText(text)
    notify.success(done)
  } catch {
    notify.error(failed)
  }
}

/**
 * The caller's certificates (F-S5b, docs/quizbuks/plans/impl/F-S5.md section 3), the same in the app and in the Sponsor
 * app: each with its issuer and the day it was lit, a revoked one marked and no longer shared, and for each the PDF
 * (issued on the first download, the stored one after), the verify link to copy and the switch that turns the link off
 * and back on. A certificate of Q77's kind keeps its end of the holding and its PDF as issued.
 */
export function CertificateList({ calls, queryKey, verifyLink, testId = 'certificate-list' }: {
  calls: CertificateCalls
  queryKey: readonly unknown[]
  /** The address of a certificate's verify page, by its key. */
  verifyLink: (verifyKey: string) => string
  testId?: string
}) {
  const { t } = useTranslation(QB)
  const certificates = useQuery({ queryKey, queryFn: () => readCertificates(calls) })
  if (certificates.isPending) return <PageState kind="loading" />
  if (certificates.isError) return <PageState kind="error" description={problemText(certificates.error)} />
  if (certificates.data.length === 0) {
    return <p className="text-muted-foreground text-sm" data-testid="certificates-none">{t('certificateList.none')}</p>
  }
  return (
    <div className="flex flex-col gap-3" data-testid={testId}>
      <ul className="flex flex-col gap-3">
        {certificates.data.map((c) => (
          <CertificateItem key={c.certificateId} certificate={c} calls={calls} queryKey={queryKey}
            link={verifyLink(c.verifyKey)} />
        ))}
      </ul>
      <p className="text-muted-foreground text-sm">{t('certificateList.hint')}</p>
    </div>
  )
}

function CertificateItem({ certificate: c, calls, queryKey, link }: {
  certificate: Certificate
  calls: CertificateCalls
  queryKey: readonly unknown[]
  link: string
}) {
  const { t } = useTranslation(QB)
  const queryClient = useQueryClient()
  const refresh = () => queryClient.invalidateQueries({ queryKey })
  const download = useMutation({
    mutationFn: () => downloadCertificate(calls, c, i18n.language),
    // The first download issued the PDF: the row now has its run.
    onSuccess: (issued) => { if (issued) void refresh() },
    onError: (error) => notify.error(problemText(error)),
  })
  const share = useMutation({
    mutationFn: (shared: boolean) => setShared(calls, c.certificateId, shared),
    onSuccess: () => refresh(),
    onError: (error) => notify.error(problemText(error)),
  })
  const node = c.source === SOURCE_NODE
  const revoked = isRevoked(c)
  const switchId = `certificate-share-${c.certificateId}`
  return (
    <li className="flex flex-col gap-2 rounded-lg border p-3" data-testid="certificate-item"
      data-certificate={c.certificateId} data-source={c.source} data-revoked={revoked}>
      <div className="flex items-start gap-2">
        <Award aria-hidden className="text-primary mt-0.5 size-5 shrink-0" />
        <div className="min-w-0">
          <p className="flex flex-wrap items-center gap-2 font-medium">
            <span data-testid="certificate-name">{certificateTitle(c, i18n.language, t)}</span>
            {revoked && <Badge variant="destructive" data-testid="certificate-revoked">{t('certificateList.revoked')}</Badge>}
          </p>
          {c.issuerName && (
            <p className="text-muted-foreground text-sm" data-testid="certificate-issuer">
              {t('certification.issuedBy', { sponsor: c.issuerName })}
            </p>
          )}
          <p className="text-muted-foreground text-sm" data-testid="certificate-dates">
            {node || !c.heldUntil
              ? t('certificateList.litOn', { date: formatDate(c.litAt) })
              : t('certificateList.period', { from: formatDate(c.litAt), until: formatDate(c.heldUntil) })}
          </p>
          {revoked && <p className="text-muted-foreground text-sm">{t('certificateList.revokedHint')}</p>}
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        {canDownload(c) && (
          <Button size="sm" variant="outline" disabled={download.isPending} onClick={() => download.mutate()}
            data-testid="certificate-download">
            <Download aria-hidden />{t('certificateList.download')}
          </Button>
        )}
        {!revoked && c.shared && (
          <Button size="sm" variant="outline" data-testid="certificate-copy"
            onClick={() => void copy(link, t('certificateList.copied'), t('certificateList.copyFailed'))}>
            <Copy aria-hidden />{t('certificateList.copy')}
          </Button>
        )}
      </div>
      {node && !revoked && (
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <Switch id={switchId} checked={c.shared} disabled={share.isPending} data-testid="certificate-share"
              aria-describedby={`${switchId}-hint`} onCheckedChange={(checked) => share.mutate(checked)} />
            <Label htmlFor={switchId}>{t('certificateList.share')}</Label>
          </div>
          <p id={`${switchId}-hint`} className="text-muted-foreground text-xs" data-testid="certificate-share-hint">
            {c.shared ? t('certificateList.shareOn') : t('certificateList.shareOff')}
          </p>
        </div>
      )}
      {!revoked && c.shared && (
        <p className="text-muted-foreground text-xs break-all" data-testid="certificate-link">{link}</p>
      )}
    </li>
  )
}
