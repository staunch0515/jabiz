import { Alert, App, Button, List, Space, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import { downloadDocument, previewDocument } from '../api/documents'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import { runProcess } from '../lib/calls'
import { formatDateTime } from '../meta/format'

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
 * exactly as issued; a preview of the document as it would be issued now; and issuing it. What the user may do is
 * only the server's to say; the buttons follow the permissions to save the user a refusal.
 */
export default function DocumentPanel({ layoutId, params, subjectId, issue, onIssued }: DocumentPanelProps) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState<'issue' | 'preview' | null>(null)
  const [error, setError] = useState<string | null>(null)
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
    message.success(t('documents.issuedOk', { number: output.documentNo ?? '' }))
    await queryClient.invalidateQueries({ queryKey: ['documents', layoutId, subjectId] })
    onIssued?.(output.runId)
  })

  const save = async (runId: string) => {
    try {
      await downloadDocument(runId)
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} data-testid={`document-panel-${layoutId}`}>
      <Space>
        {can('document.issue') && (
          <Button loading={busy === 'preview'} disabled={busy !== null} data-testid="document-preview"
            onClick={() => void act('preview', () => previewDocument(layoutId, { params }))}>
            {t('documents.preview')}
          </Button>
        )}
        <Button type="primary" loading={busy === 'issue'} disabled={busy !== null} data-testid="document-issue"
          onClick={() => void doIssue()}>
          {t('documents.issue')}
        </Button>
      </Space>
      {error && <Alert type="error" showIcon message={error} data-testid="document-error" />}
      {canRead && subjectId !== undefined && (
        <List
          size="small"
          loading={issued.isLoading}
          dataSource={issued.data ?? []}
          locale={{ emptyText: t('documents.none') }}
          renderItem={(run) => (
            <List.Item
              key={run.runId}
              actions={[
                <a key="download" onClick={() => void save(run.runId!)} data-testid={`document-download-${run.runId}`}>
                  {t('documents.download')}
                </a>,
              ]}
            >
              <Space direction="vertical" size={0}>
                <span>{run.documentNo ?? run.title}</span>
                <Typography.Text type="secondary">
                  {run.issuedTime ? formatDateTime(run.issuedTime) : ''} · {run.issuedBy}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      )}
    </Space>
  )
}
