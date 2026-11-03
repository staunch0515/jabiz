import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  AlertDescription,
  ApiError,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  RadioGroup,
  RadioGroupItem,
  Textarea,
} from '@jabiz/admin'
import { BILLING_MODES, setBilling, type BillingMode } from './fee-api'
import { parseAmount } from './finance'

/** What the dialog needs of a sponsor: the sponsors' page and the billing page give it from their rows. */
export interface BilledSponsor {
  userId: string
  company: string
  billingMode: BillingMode
  creditLimit: string
}

/**
 * Sets how a sponsor of the operator's is billed for the platform's fees (`QB_OPERATOR_BILLING_SET`, Q87): prepaid
 * (taken from the cash account when charged) or postpaid within a credit, with a reason headquarters reads. The server
 * refuses a sponsor that is not the operator's own, and a credit on a prepaid sponsor.
 */
export function BillingDialog({ sponsor, onClose, onSaved }: { sponsor: BilledSponsor; onClose: () => void;
  onSaved?: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const [mode, setMode] = useState<BillingMode>(sponsor.billingMode)
  const [credit, setCredit] = useState(sponsor.billingMode === 'POSTPAID' ? sponsor.creditLimit : '0')
  const [reason, setReason] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const postpaid = mode === 'POSTPAID'
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const limit = postpaid ? parseAmount(credit) : 0
    const bad = limit === null || limit < 0
    setInvalid(bad)
    if (bad || busy || !reason.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      const output = await setBilling({ userId: sponsor.userId, billingMode: mode, creditLimit: limit!, reason: reason.trim() })
      notify.success(t(output.changed ? 'operator.billing.saved' : 'operator.billing.unchanged', { name: sponsor.company }))
      onSaved?.()
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="billing-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('operator.billing.title', { name: sponsor.company })}</DialogTitle>
            <DialogDescription>{t('operator.billing.hint')}</DialogDescription>
          </DialogHeader>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('operator.billing.mode')}</legend>
            <RadioGroup value={mode} onValueChange={(value) => setMode(value as BillingMode)} data-testid="billing-mode">
              {BILLING_MODES.map((value) => (
                <div key={value} className="flex items-start gap-2">
                  <RadioGroupItem id={`${id}-mode-${value}`} value={value} className="mt-1"
                    aria-describedby={`${id}-mode-${value}-hint`} data-testid={`billing-mode-${value}`} />
                  <div className="flex flex-col">
                    <Label htmlFor={`${id}-mode-${value}`}>{t(`operator.billing.modes.${value}`)}</Label>
                    <span id={`${id}-mode-${value}-hint`} className="text-muted-foreground text-sm">
                      {t(`operator.billing.modeHints.${value}`)}
                    </span>
                  </div>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          {postpaid && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-credit`}>{t('operator.billing.creditLimit')}</Label>
              <Input id={`${id}-credit`} value={credit} inputMode="numeric" autoComplete="off" required maxLength={17}
                aria-invalid={invalid ? true : undefined}
                aria-describedby={`${id}-credit-hint${invalid ? ` ${id}-credit-error` : ''}`}
                data-testid="billing-credit" onChange={(event) => {
                  setCredit(event.target.value)
                  setInvalid(false)
                }} />
              <p id={`${id}-credit-hint`} className="text-muted-foreground text-sm">{t('operator.billing.creditLimitHint')}</p>
              {invalid && <p id={`${id}-credit-error`} className="text-destructive text-sm">{t('operator.billing.invalid')}</p>}
            </div>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-reason`}>{t('operator.billing.reason')}</Label>
            <Textarea id={`${id}-reason`} value={reason} maxLength={500} required aria-describedby={`${id}-reason-hint`}
              data-testid="billing-reason" onChange={(event) => setReason(event.target.value)} />
            <p id={`${id}-reason-hint`} className="text-muted-foreground text-sm">{t('operator.billing.reasonHint')}</p>
          </div>
          {problem && <Alert variant="destructive" data-testid="billing-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('operator.cancel')}</Button>
            <Button type="submit" disabled={busy || !reason.trim()} aria-busy={busy || undefined} data-testid="billing-submit">
              {t('operator.billing.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
