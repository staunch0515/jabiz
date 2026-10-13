import { Alert, AlertDescription, Button, notify, Spinner, Textarea } from '@jabiz/ui'
import { CircleAlert } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../api/problem'
import { runProcess } from '../lib/calls'

export interface ApprovalPanelProps {
  /** The approval request to decide (a task's `subjectId`). */
  requestId: string
  /** Called with the request's new status once the decision is taken. */
  onDecided?: (status: string) => void
}

/**
 * Approves or rejects the current level of an approval request through `APPROVAL_DECIDE`
 * (docs/design/18-numbering-approvals-tasks.md section 3.4). A rejection needs a reason. Whether the user may decide
 * (level permission, limit, not the preparer) is only the server's to say: its refusal is shown as it comes.
 */
export default function ApprovalPanel({ requestId, onDecided }: ApprovalPanelProps) {
  const { t } = useTranslation()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState<'APPROVE' | 'REJECT' | null>(null)
  const [error, setError] = useState<string | null>(null)

  const decide = async (decision: 'APPROVE' | 'REJECT') => {
    setBusy(decision)
    setError(null)
    try {
      const output = await runProcess<{ status: string }>('APPROVAL_DECIDE', {
        requestId,
        decision,
        reason: reason.trim() || undefined,
      })
      notify.success(t(decision === 'APPROVE' ? 'approval.approved' : 'approval.rejected'))
      onDecided?.(output.status)
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="flex w-full flex-col gap-2" data-testid={`approval-panel-${requestId}`}>
      <Textarea
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder={t('approval.reason')}
        aria-label={t('approval.reason')}
        maxLength={500}
        rows={2}
        className="min-h-9"
      />
      {error && (
        <Alert variant="destructive" data-testid="approval-error">
          <CircleAlert aria-hidden />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        <Button disabled={busy !== null} onClick={() => void decide('APPROVE')} data-testid="approval-approve">
          {busy === 'APPROVE' && <Spinner className="text-current" />}
          {t('approval.approve')}
        </Button>
        <Button
          variant="destructive"
          disabled={busy !== null || !reason.trim()}
          onClick={() => void decide('REJECT')}
          data-testid="approval-reject"
        >
          {busy === 'REJECT' && <Spinner className="text-current" />}
          {t('approval.reject')}
        </Button>
      </div>
    </div>
  )
}
