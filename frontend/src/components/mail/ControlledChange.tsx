import { ApiError, runProcess, UserName, useAuth } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Button, Label, notify, Spinner, Textarea } from '@jabiz/ui'
import { Hourglass } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { CONTROL_CHANGE_DATASET, CONTROL_CHANGE_WITHDRAW, type PendingChange } from '../../api/mail'
import { paths } from '../../pages/paths'
import DiffView from './DiffView'
import type { Values, VersionField } from './VersionHistory'

export interface PendingChangesProps {
  changes: PendingChange[]
  fields: VersionField[]
  /** What is in effect now, which a proposal is compared with. */
  current: Values
  onWithdrawn: () => void
}

/**
 * Changes proposed and waiting for another administrator: who proposed them (a name), why, what they change, and
 * where they are published (the platform's list of controlled changes). The proposer may withdraw their own.
 */
export function PendingChanges({ changes, fields, current, onWithdrawn }: PendingChangesProps) {
  const { t } = useTranslation()
  const { userId } = useAuth()
  const [busy, setBusy] = useState<string | null>(null)
  if (changes.length === 0) return null

  const withdraw = async (changeId: string) => {
    setBusy(changeId)
    try {
      await runProcess(CONTROL_CHANGE_WITHDRAW, { changeId })
      notify.success(t('mail.pending.withdrawn'))
      onWithdrawn()
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="flex flex-col gap-3" data-testid="mail-pending">
      {changes.map((change) => (
        <Alert key={change.changeId} data-testid="mail-pending-change">
          <Hourglass aria-hidden />
          <AlertTitle>{t('mail.pending.title')}</AlertTitle>
          <AlertDescription className="text-foreground flex flex-col gap-2">
            <p>
              {t('mail.pending.proposedBy')} <UserName id={change.proposedBy} />
              {change.reason && <> · {t('mail.pending.reason')}: {change.reason}</>}
            </p>
            <details>
              <summary className="cursor-pointer">{t('mail.pending.show')}</summary>
              <div className="mt-2 flex flex-col gap-2">
                {fields.map((field) => (
                  <DiffView
                    key={field.name}
                    label={field.label}
                    sides="proposal"
                    older={current[field.name]}
                    newer={change.values[field.name] == null ? '' : String(change.values[field.name])}
                  />
                ))}
              </div>
            </details>
            <p className="text-muted-foreground">{t('mail.pending.hint')}</p>
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" size="sm" asChild>
                <Link to={paths.dataset(CONTROL_CHANGE_DATASET)} data-testid="mail-pending-link">
                  {t('mail.pending.open')}
                </Link>
              </Button>
              {userId && change.proposedBy === userId && (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy === change.changeId}
                  onClick={() => void withdraw(change.changeId)}
                  data-testid="mail-pending-withdraw"
                >
                  {t('mail.pending.withdraw')}
                </Button>
              )}
            </div>
          </AlertDescription>
        </Alert>
      ))}
    </div>
  )
}

export interface ProposeFormProps {
  /** Proposes the change with the reason; rejects with what went wrong (shown by the caller). */
  onPropose: (reason: string) => Promise<void>
  disabled?: boolean
}

/**
 * Proposing a change another administrator publishes: a reason (required, as the controlled change requires it), and
 * the button. A recent second factor is asked for by the platform's step-up when the server requires it.
 */
export function ProposeForm({ onPropose, disabled }: ProposeFormProps) {
  const { t } = useTranslation()
  const id = useId()
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    if (reason.trim() === '') {
      setMissing(true)
      return
    }
    setBusy(true)
    try {
      await onPropose(reason.trim())
      setReason('')
    } catch {
      // The caller showed why.
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      noValidate
      className="flex flex-col gap-2 rounded-md border p-4"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
      data-testid="mail-propose"
    >
      <p className="text-muted-foreground text-sm">{t('mail.propose.hint')}</p>
      <Label htmlFor={id}>{t('mail.propose.reason')}</Label>
      <Textarea
        id={id}
        value={reason}
        maxLength={500}
        required
        aria-invalid={missing || undefined}
        aria-describedby={missing ? `${id}-error` : undefined}
        onChange={(event) => {
          setReason(event.target.value)
          setMissing(false)
        }}
        data-testid="mail-propose-reason"
      />
      {missing && (
        <p id={`${id}-error`} className="text-destructive text-sm">
          {t('mail.propose.reasonRequired')}
        </p>
      )}
      <div>
        <Button type="submit" disabled={disabled || busy} aria-busy={busy || undefined} data-testid="mail-propose-submit">
          {busy && <Spinner className="text-current" />}
          {t('mail.propose.submit')}
        </Button>
      </div>
    </form>
  )
}
