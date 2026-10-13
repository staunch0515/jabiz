import {
  Alert,
  AlertDescription,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  notify,
  Spinner,
  TagsInput,
  UI_NAMESPACE,
} from '@jabiz/ui'
import { CircleAlert, Plus } from 'lucide-react'
import { useId, useState } from 'react'
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

/** Several addresses typed at once: separated by commas (the field's own), semicolons or spaces. */
function splitAddresses(addresses: string[]): string[] {
  return [...new Set(addresses.flatMap((a) => a.split(/[;\s]+/)).filter((a) => a !== ''))]
}

/**
 * Sends an issued document by e-mail through `DOCUMENT_SEND` (docs/design/22-documents.md section 5): its kept PDF
 * attached, one message per address. The addresses its data names are offered; whether another address is allowed
 * (`document.send.any`), and whether an address is valid, is the server's to say: its refusal is shown as it comes.
 * Give it a `key` of the document, so that its addresses start afresh for each one.
 */
export default function SendDocumentModal({ runId, recipients, onClose, onSent }: SendDocumentModalProps) {
  const { t } = useTranslation()
  const fieldId = useId()
  const [addresses, setAddresses] = useState<string[]>(recipients)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // The document's own addresses taken out of the list, offered back with a button each.
  const removed = recipients.filter((address) => !addresses.includes(address))

  const send = async () => {
    if (!runId || addresses.length === 0) return
    setBusy(true)
    setError(null)
    try {
      const output = await runProcess<{ addresses: string[] }>('DOCUMENT_SEND', { runId, to: addresses })
      notify.success(t('documents.sentOk', { count: output.addresses.length }))
      onSent?.(output.addresses)
      onClose()
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={runId !== undefined} onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault()
            void send()
          }}
        >
          <DialogHeader>
            <DialogTitle>{t('documents.sendTitle')}</DialogTitle>
            <DialogDescription>{t('documents.recipientsHint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={fieldId}>{t('documents.recipients')}</Label>
            <div data-testid="document-send-to">
              <TagsInput
                id={fieldId}
                value={addresses}
                onChange={(next) => setAddresses(splitAddresses(next))}
                autoComplete="email"
              />
            </div>
            {removed.length > 0 && (
              <div className="flex flex-wrap gap-1">
                {removed.map((address) => (
                  <Button
                    key={address}
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setAddresses([...addresses, address])}
                  >
                    <Plus aria-hidden />
                    {address}
                  </Button>
                ))}
              </div>
            )}
          </div>
          {error && (
            <Alert variant="destructive" data-testid="document-send-error">
              <CircleAlert aria-hidden />
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={busy}>
              {t('confirm.cancel', { ns: UI_NAMESPACE })}
            </Button>
            <Button type="submit" disabled={busy || addresses.length === 0} data-testid="document-send-submit">
              {busy && <Spinner className="text-current" />}
              {t('documents.send')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
