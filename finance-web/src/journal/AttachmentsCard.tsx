import { useQueryClient } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, fetchFileDownload, runProcess, uploadFile, useAuth } from '@jabiz/admin'
import { Alert, App, Button, Card, Input, List, Space, Typography } from 'antd'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FILE_POLICIES, isSpreadsheet, PERMISSIONS, PROCESSES, type Attachment, type Journal } from './api'

/**
 * The supporting documents of an entry (FIN-GL-016) with their content hashes. The preparer adds and removes them
 * while the entry is a draft or rejected; once posted they are evidence and stay.
 */
export default function AttachmentsCard({ journal, attachments, editable }: {
  journal: Journal
  attachments: Attachment[]
  editable: boolean
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { message } = App.useApp()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const picker = useRef<HTMLInputElement>(null)
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const changeable = editable && can(PERMISSIONS.attach) && (journal.status === 'DRAFT' || journal.status === 'REJECTED')
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['fin', 'journal', journal.journalId] })

  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await action()
      await refresh()
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  const attach = (file: File) =>
    run(async () => {
      const sheet = isSpreadsheet(file)
      const uploaded = await uploadFile(sheet ? FILE_POLICIES.sheets : FILE_POLICIES.support, file, file.name)
      await runProcess(PROCESSES.attach, {
        journalId: journal.journalId,
        [sheet ? 'sheetFileId' : 'fileId']: uploaded.fileId,
        description: description.trim() || undefined,
      })
      setDescription('')
      message.success(t('journal.attached'))
    })

  const download = async (attachment: Attachment) => {
    try {
      const { blob, fileName } = await fetchFileDownload((attachment.fileId ?? attachment.sheetFileId) as string)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = fileName ?? 'attachment'
      link.click()
      // Released once the browser has taken the download over.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    }
  }

  return (
    <Card size="small" title={t('journal.attachments')} data-testid="journal-attachments">
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 8 }} />}
      <List
        size="small"
        dataSource={attachments}
        locale={{ emptyText: t('journal.noAttachments') }}
        renderItem={(attachment) => (
          <List.Item
            actions={[
              <Button key="open" type="link" onClick={() => void download(attachment)}>
                {t('journal.open')}
              </Button>,
              ...(changeable
                ? [
                    <Button key="remove" type="link" danger disabled={busy}
                      onClick={() => void run(() => runProcess(PROCESSES.detach, { attachmentId: attachment.attachmentId }).then(() => undefined))}>
                      {t('journal.remove')}
                    </Button>,
                  ]
                : []),
            ]}
          >
            <Space direction="vertical" size={0}>
              <span>{attachment.description || t(attachment.sheetFileId ? 'journal.spreadsheet' : 'journal.document')}</span>
              <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable={{ text: attachment.sha256 }}>
                SHA-256 {attachment.sha256.slice(0, 16)}…
              </Typography.Text>
            </Space>
          </List.Item>
        )}
      />
      {changeable && (
        <Space.Compact style={{ width: '100%', marginTop: 8 }}>
          <Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={200}
            placeholder={t('journal.attachmentDescription')} aria-label={t('journal.attachmentDescription')} />
          <Button loading={busy} onClick={() => picker.current?.click()}>
            {t('journal.attach')}
          </Button>
          <input ref={picker} type="file" hidden aria-label={t('journal.attachFile')}
            accept=".pdf,.png,.jpg,.jpeg,.csv,.xlsx" data-testid="attachment-input"
            onChange={(e) => {
              const file = e.target.files?.[0]
              e.target.value = ''
              if (file) void attach(file)
            }} />
        </Space.Compact>
      )}
    </Card>
  )
}
