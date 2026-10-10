import { PageContainer, ProTable, type ProColumns } from '@ant-design/pro-components'
import { App, Space, Tag, Tooltip, Typography } from 'antd'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { downloadDocument } from '../api/documents'
import { ApiError } from '../api/problem'
import type { components } from '@jabiz/client'
import { useAuth } from '../auth/AuthContext'
import DocumentDeliveries from '../components/DocumentDeliveries'
import SendDocumentModal from '../components/SendDocumentModal'
import { expandIcon } from '../components/expandIcon'
import { formatDateTime } from '../meta/format'

type DocumentSummary = components['schemas']['DocumentSummary']
type DocumentVerification = components['schemas']['DocumentVerification']

/**
 * Issued documents (docs/design/22-documents.md section 6): newest first, optionally of one layout (`?layout=`) or
 * about one subject (`?subject=`). Each is saved exactly as issued - the kept PDF - and can be verified: the kept
 * bytes against their hash, and the data at the document's point in time against its content hash - and sent by
 * e-mail, each row opening onto the addresses it went to. The server lists only documents the user may read.
 */
export default function DocumentsPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [searchParams] = useSearchParams()
  const layout = searchParams.get('layout') ?? undefined
  const subject = searchParams.get('subject') ?? undefined
  const [verdicts, setVerdicts] = useState<Record<string, DocumentVerification>>({})
  const [sending, setSending] = useState<DocumentSummary | undefined>()
  const queryClient = useQueryClient()
  const { can } = useAuth()

  const verify = async (runId: string) => {
    try {
      const result = await unwrap(api.POST('/api/documents/runs/{runId}/verify', { params: { path: { runId } } }))
      setVerdicts((current) => ({ ...current, [runId]: result }))
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  const save = async (runId: string) => {
    try {
      await downloadDocument(runId)
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  const columns: ProColumns<DocumentSummary>[] = [
    {
      title: t('documents.document'),
      dataIndex: 'title',
      render: (_, run) => (
        <Space direction="vertical" size={0}>
          <span>{run.title} {run.documentNo}</span>
          <Typography.Text type="secondary">{run.layoutId}</Typography.Text>
        </Space>
      ),
    },
    {
      title: t('documents.subject'),
      dataIndex: 'subjectId',
      render: (_, run) => run.subjectId
        ? <Typography.Text type="secondary">{run.subjectEntity} {run.subjectId}</Typography.Text>
        : '',
    },
    {
      title: t('documents.issued'),
      dataIndex: 'issuedTime',
      render: (_, run) => (
        <Space direction="vertical" size={0}>
          <span>{run.issuedTime ? formatDateTime(run.issuedTime) : ''}</span>
          <Typography.Text type="secondary">{run.issuedBy}</Typography.Text>
        </Space>
      ),
    },
    { title: t('documents.pages'), dataIndex: 'pages', align: 'right' },
    {
      title: t('documents.hash'),
      dataIndex: 'pdfHash',
      render: (_, run) => (
        <Tooltip title={run.pdfHash}>
          <Typography.Text code>{run.pdfHash?.slice(0, 12)}</Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: t('documents.status'),
      key: 'status',
      render: (_, run) => {
        const verdict = verdicts[run.runId ?? '']
        if (!verdict) return null
        return (
          <Space wrap>
            {!verdict.copyIntact && <Tag color="red" data-testid={`copy-${run.runId}`}>{t('documents.copyAltered')}</Tag>}
            <Tag color={verdict.verdict === 'identical' ? 'green' : 'red'} data-testid={`verdict-${run.runId}`}>
              {t(`documents.verdict.${verdict.verdict}`)}
            </Tag>
          </Space>
        )
      },
    },
    {
      title: t('list.actions'),
      key: 'actions',
      valueType: 'option',
      render: (_, run) => [
        <a key="download" onClick={() => void save(run.runId!)} data-testid={`document-download-${run.runId}`}>
          {t('documents.download')}
        </a>,
        <a key="verify" onClick={() => void verify(run.runId!)} data-testid={`document-verify-${run.runId}`}>
          {t('documents.verify')}
        </a>,
        ...(can('document.send')
          ? [<a key="send" onClick={() => setSending(run)} data-testid={`document-send-${run.runId}`}>
              {t('documents.send')}
            </a>]
          : []),
      ],
    },
  ]

  return (
    <PageContainer title={t('documents.title')}>
      <ProTable<DocumentSummary>
        rowKey="runId"
        columns={columns}
        search={false}
        params={{ layout, subject }}
        pagination={false}
        data-testid="documents"
        expandable={{ expandedRowRender: (run) => <DocumentDeliveries runId={run.runId!} />, expandIcon: expandIcon(t) }}
        request={async () => {
          try {
            const runs = await unwrap(
              api.GET('/api/documents/runs', { params: { query: { layout, subject, limit: 200 } } }),
            )
            return { data: runs, success: true }
          } catch (e) {
            message.error(e instanceof ApiError ? e.display : String(e))
            return { data: [], success: false }
          }
        }}
      />
      <SendDocumentModal
        key={sending?.runId ?? 'none'}
        runId={sending?.runId}
        recipients={sending?.recipients ?? []}
        onClose={() => setSending(undefined)}
        onSent={() => void queryClient.invalidateQueries({ queryKey: ['document', sending?.runId] })}
      />
    </PageContainer>
  )
}
