import { Alert, App, Modal, Select, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../api/problem'
import { runProcess } from '../lib/calls'

export interface SendDocumentModalProps {
  /** The issued document to send; the dialog is closed when absent. */
  runId?: string
  /** The addresses its data names, offered by default. */
  recipients: string[]
  onClose: () => void
  /** Called once the deliveries are recorded (the e-mails leave after the commit). */
  onSent?: (addresses: string[]) => void
}

/**
 * Sends an issued document by e-mail through `DOCUMENT_SEND` (docs/design/22-documents.md section 5): its kept PDF
 * attached, one message per address. The addresses its data names are offered; whether another address is allowed
 * (`document.send.any`), and whether an address is valid, is the server's to say: its refusal is shown as it comes.
 * Give it a `key` of the document, so that its addresses start afresh for each one.
 */
export default function SendDocumentModal({ runId, recipients, onClose, onSent }: SendDocumentModalProps) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [addresses, setAddresses] = useState<string[]>(recipients)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const send = async () => {
    if (!runId) return
    setBusy(true)
    setError(null)
    try {
      const output = await runProcess<{ addresses: string[] }>('DOCUMENT_SEND', { runId, to: addresses })
      message.success(t('documents.sentOk', { count: output.addresses.length }))
      onSent?.(output.addresses)
      onClose()
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open={runId !== undefined}
      title={t('documents.sendTitle')}
      okText={t('documents.send')}
      onOk={() => void send()}
      okButtonProps={{ disabled: addresses.length === 0, loading: busy }}
      onCancel={onClose}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">{t('documents.recipientsHint')}</Typography.Paragraph>
      <Select
        mode="tags"
        style={{ width: '100%' }}
        value={addresses}
        onChange={setAddresses}
        tokenSeparators={[',', ';', ' ']}
        options={recipients.map((address) => ({ value: address, label: address }))}
        aria-label={t('documents.recipients')}
        data-testid="document-send-to"
      />
      {error && <Alert style={{ marginTop: 12 }} type="error" showIcon message={error} data-testid="document-send-error" />}
    </Modal>
  )
}
