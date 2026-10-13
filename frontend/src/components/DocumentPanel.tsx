import { Alert, AlertDescription, Button, notify, Spinner } from '@jabiz/ui'
import { CircleAlert } from 'lucide-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import { downloadDocument, previewDocument } from '../api/documents'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import { runProcess } from '../lib/calls'
import { formatDateTime } from '../meta/format'
import SendDocumentModal from './SendDocumentModal'

export interface DocumentPanelProps {
  /** The document layout. */
  layoutId: string
  /** The parameters of its templates, such as `{ invoiceId }`; also what a preview reads. */
  params: Record<string, unknown>
  /** The id of the document's subject: the documents already issued about it are listed. */
  subjectId?: string
  /**
   * The process that issues the document, with its input; `DOCUMENT_ISSUE` with the layout and parameters when absent.
   * An application's own process decides what the platform cannot, such as the point in time (the document's date).
   */
  issue?: { process: string; input: Record<string, unknown> }
  /** Called with the new document's id once it is issued. */
  onIssued?: (runId: string) => void
}

/**
 * A business document of one subject (docs/design/22-documents.md section 6): the copies issued so far, each saved
 * exactly as issued or sent by e-mail; a preview of the document as it would be issued now; and issuing it. What the user may do is
 * only the server's to say; the buttons follow the permissions to save the user a refusal.
 */
export default function DocumentPanel({ layoutId, params, subjectId, issue, onIssued }: DocumentPanelProps) {
  const { t } = useTranslation()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState<'issue' | 'preview' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState<{ runId: string; recipients: string[] } | undefined>()
  const canRead = can('document.archive.read')
  const issued = useQuery({
    queryKey: ['documents', layoutId, subjectId],
    enabled: canRead && subjectId !== undefined,
    queryFn: () => unwrap(api.GET('/api/documents/runs', {
      params: { query: { layout: layoutId, subject: subjectId, limit: 50 } },
    })),
  })

  const act = async (what: 'issue' | 'preview', action: () => Promise<void>) => {
    setBusy(what)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(null)
    }
  }

  const doIssue = () => act('issue', async () => {
    const output = await runProcess<{ runId: string; documentNo?: string }>(
      issue?.process ?? 'DOCUMENT_ISSUE',
      issue?.input ?? { layoutId, params },
    )
    notify.success(t('documents.issuedOk', { number: output.documentNo ?? '' }))
    await queryClient.invalidateQueries({ queryKey: ['documents', layoutId, subjectId] })
    onIssued?.(output.runId)
  })

  const save = async (runId: string) => {
    try {
      await downloadDocument(runId)
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  return (
    <div className="flex w-full flex-col gap-3" data-testid={`document-panel-${layoutId}`}>
      <div className="flex flex-wrap gap-2">
        {can('document.issue') && (
          <Button variant="outline" disabled={busy !== null} data-testid="document-preview"
            onClick={() => void act('preview', () => previewDocument(layoutId, { params }))}>
            {busy === 'preview' && <Spinner className="text-current" />}
            {t('documents.preview')}
          </Button>
        )}
        <Button disabled={busy !== null} data-testid="document-issue" onClick={() => void doIssue()}>
          {busy === 'issue' && <Spinner className="text-current" />}
          {t('documents.issue')}
        </Button>
      </div>
      {error && (
        <Alert variant="destructive" data-testid="document-error">
          <CircleAlert aria-hidden />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {canRead && subjectId !== undefined && (
        issued.isLoading ? (
          <Spinner />
        ) : (issued.data ?? []).length === 0 ? (
          <p className="text-muted-foreground text-sm">{t('documents.none')}</p>
        ) : (
          <ul className="divide-border divide-y rounded-md border" data-testid="document-copies">
            {(issued.data ?? []).map((run) => (
              <li key={run.runId} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
                <span className="flex flex-col">
                  <span>{run.documentNo ?? run.title}</span>
                  <span className="text-muted-foreground text-xs">
                    {run.issuedTime ? formatDateTime(run.issuedTime) : ''} · {run.issuedBy}
                  </span>
                </span>
                <span className="flex items-center gap-1">
                  <Button variant="link" size="sm" className="h-auto px-1" onClick={() => void save(run.runId!)}
                    data-testid={`document-download-${run.runId}`}>
                    {t('documents.download')}
                  </Button>
                  {can('document.send') && (
                    <Button variant="link" size="sm" className="h-auto px-1" data-testid={`document-send-${run.runId}`}
                      onClick={() => setSending({ runId: run.runId!, recipients: run.recipients ?? [] })}>
                      {t('documents.send')}
                    </Button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )
      )}
      <SendDocumentModal
        key={sending?.runId ?? 'none'}
        runId={sending?.runId}
        recipients={sending?.recipients ?? []}
        onClose={() => setSending(undefined)}
      />
    </div>
  )
}
