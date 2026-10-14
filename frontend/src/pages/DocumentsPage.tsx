import { api, unwrap, ApiError, useAuth, UserName, formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  cn,
  CopyButton,
  DataTable,
  notify,
  UI_NAMESPACE,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { downloadDocument } from '../api/documents'
import type { components } from '@jabiz/client'
import DocumentDeliveries from '../components/DocumentDeliveries'
import SendDocumentModal from '../components/SendDocumentModal'
import AdminPage from '../layout/AdminPage'

type DocumentSummary = components['schemas']['DocumentSummary']
type DocumentVerification = components['schemas']['DocumentVerification']

const LINK = 'h-auto px-1'

const fail = (e: unknown) => notify.error(e instanceof ApiError ? e.display : String(e))

/**
 * The verdicts of the documents verified on this page. A cell reads them from here rather than from the column
 * definitions: the table mounts its cells anew when its columns change, which would take focus from the "Verify"
 * button the user just pressed.
 */
const VerdictsContext = createContext<Record<string, DocumentVerification>>({})

/** A document's verdict, once verified: whether the kept copy is intact, and whether it matches the data. */
function VerdictBadges({ runId }: { runId: string }) {
  const { t } = useTranslation()
  const verdict = useContext(VerdictsContext)[runId]
  if (!verdict) return null
  return (
    <span className="flex flex-wrap items-center gap-1">
      {!verdict.copyIntact && (
        <Badge variant="destructive" data-testid={`copy-${runId}`}>
          {t('documents.copyAltered')}
        </Badge>
      )}
      <Badge variant={verdict.verdict === 'identical' ? 'success' : 'destructive'} data-testid={`verdict-${runId}`}>
        {t(`documents.verdict.${verdict.verdict}`)}
      </Badge>
    </span>
  )
}

/**
 * Issued documents (docs/design/22-documents.md section 6): newest first, optionally of one layout (`?layout=`) or
 * about one subject (`?subject=`). Each is saved exactly as issued - the kept PDF - and can be verified: the kept
 * bytes against their hash, and the data at the document's point in time against its content hash - and sent by
 * e-mail, each row opening onto the addresses it went to. The server lists only documents the user may read.
 */
export default function DocumentsPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const layout = searchParams.get('layout') ?? undefined
  const subject = searchParams.get('subject') ?? undefined
  const [verdicts, setVerdicts] = useState<Record<string, DocumentVerification>>({})
  const [sending, setSending] = useState<DocumentSummary | undefined>()
  const queryClient = useQueryClient()
  const { can } = useAuth()
  const canSend = can('document.send')

  const runs = useQuery({
    queryKey: ['documents', 'runs', layout, subject],
    queryFn: () => unwrap(api.GET('/api/documents/runs', { params: { query: { layout, subject, limit: 200 } } })),
  })
  useEffect(() => {
    if (runs.error) fail(runs.error)
  }, [runs.error])

  // Stable while verdicts arrive (they come through VerdictsContext), so no cell is mounted anew.
  const columns = useMemo<ColumnDef<DocumentSummary, unknown>[]>(() => {
    const verify = async (runId: string) => {
      try {
        const result = await unwrap(api.POST('/api/documents/runs/{runId}/verify', { params: { path: { runId } } }))
        setVerdicts((current) => ({ ...current, [runId]: result }))
      } catch (e) {
        fail(e)
      }
    }
    const save = async (runId: string) => {
      try {
        await downloadDocument(runId)
      } catch (e) {
        fail(e)
      }
    }
    const secondary = 'text-muted-foreground text-xs'
    // Ids (the subject's, an issuer shown by id) break at their hyphens rather than widen the table: cells do not
    // wrap, and a table wider than the page slides its last columns under the pinned actions, the hash's copy
    // button among them (covered, it is no target one can use).
    const wrapping = 'inline-block max-w-44 whitespace-normal'
    return [
      {
        id: 'title',
        header: t('documents.document'),
        cell: ({ row: { original: run } }) => (
          <span className="flex flex-col">
            <span>
              {run.title} {run.documentNo}
            </span>
            <span className={secondary}>{run.layoutId}</span>
          </span>
        ),
      },
      {
        id: 'subject',
        header: t('documents.subject'),
        cell: ({ row: { original: run } }) =>
          run.subjectId ? (
            <span className={cn(secondary, wrapping)}>{`${run.subjectEntity} ${run.subjectId}`}</span>
          ) : null,
      },
      {
        id: 'issued',
        header: t('documents.issued'),
        cell: ({ row: { original: run } }) => (
          <span className="flex flex-col">
            <span>{run.issuedTime ? formatDateTime(run.issuedTime) : ''}</span>
            <span className={cn(secondary, wrapping)}>
              <UserName id={run.issuedBy} />
            </span>
          </span>
        ),
      },
      {
        id: 'pages',
        header: t('documents.pages'),
        meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => row.original.pages,
      },
      {
        id: 'hash',
        header: t('documents.hash'),
        cell: ({ row: { original: run } }) =>
          run.pdfHash && (
            <span className="inline-flex items-center gap-1 whitespace-nowrap">
              <code className="font-mono text-xs" title={run.pdfHash}>
                {run.pdfHash.slice(0, 12)}
              </code>
              <CopyButton
                value={run.pdfHash}
                label={t('copy.copyValue', { ns: UI_NAMESPACE, value: t('documents.hash') })}
              />
            </span>
          ),
      },
      {
        id: 'status',
        header: t('documents.status'),
        cell: ({ row }) => <VerdictBadges runId={row.original.runId ?? ''} />,
      },
      {
        id: 'actions',
        header: t('list.actions'),
        meta: { pinned: 'right' },
        cell: ({ row: { original: run } }) => (
          <span className="flex flex-wrap items-center gap-1">
            <Button variant="link" size="sm" className={LINK} onClick={() => void save(run.runId!)}
              data-testid={`document-download-${run.runId}`}>
              {t('documents.download')}
            </Button>
            <Button variant="link" size="sm" className={LINK} onClick={() => void verify(run.runId!)}
              data-testid={`document-verify-${run.runId}`}>
              {t('documents.verify')}
            </Button>
            {canSend && (
              <Button variant="link" size="sm" className={LINK} onClick={() => setSending(run)}
                data-testid={`document-send-${run.runId}`}>
                {t('documents.send')}
              </Button>
            )}
          </span>
        ),
      },
    ]
  }, [t, canSend])

  return (
    <AdminPage title={t('documents.title')}>
      <div data-testid="documents">
        <VerdictsContext.Provider value={verdicts}>
          <DataTable
            label={t('documents.title')}
            columns={columns}
            data={runs.data ?? []}
            getRowId={(run) => run.runId ?? ''}
            loading={runs.isLoading}
            renderExpanded={(run) => <DocumentDeliveries runId={run.runId!} />}
          />
        </VerdictsContext.Provider>
      </div>
      <SendDocumentModal
        key={sending?.runId ?? 'none'}
        runId={sending?.runId}
        recipients={sending?.recipients ?? []}
        onClose={() => setSending(undefined)}
        onSent={() => void queryClient.invalidateQueries({ queryKey: ['document', sending?.runId] })}
      />
    </AdminPage>
  )
}
