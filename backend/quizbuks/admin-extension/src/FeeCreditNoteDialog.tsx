import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  AlertDescription,
  ApiError,
  Button,
  DescriptionList,
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
  Textarea,
} from '@jabiz/admin'
import { LoadFailed } from './components'
import { FEES_KEY, readChargeCredit, recordFeeRequest } from './fee-api'
import { parseAmount } from './finance'
import { money } from './hooks'

/**
 * F-M7: issues a credit note by hand on a fee (`QB_FEE_REQUEST`, kind `CREDIT_NOTE`; another administrator approves).
 * It first reads what the fee may still be credited (`qb.admin.fee-charge-credit`: before tax, with the tax a note
 * that credits all the rest takes), then asks the amount (from 1 up to that) and the reason. Nothing is issued here:
 * the server checks the limit again at the request and at the approval, and its refusals (`QB_FEE_CREDIT_NOTE_EXCEEDS`,
 * `QB_FEE_CREDIT_NOTE_VOIDED`) are shown as it words them.
 */
export function FeeCreditNoteDialog({ chargeId, onClose }: { chargeId: string; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const credit = useQuery({ queryKey: [...FEES_KEY, 'charge-credit', chargeId], queryFn: () => readChargeCredit(chargeId) })
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const data = credit.data
  const creditable = data ? Number(data.creditable) : 0
  const voided = data?.invoiceStatus === 'VOID'
  const open = !!data && creditable > 0 && !voided
  const complete = open && !!reason.trim()
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const value = parseAmount(amount)
    const bad = value === null || value > creditable
    setInvalid(bad)
    if (bad || busy || !complete) return
    setBusy(true)
    setProblem(null)
    try {
      await recordFeeRequest({ kind: 'CREDIT_NOTE', chargeId, amount: value, reason: reason.trim() })
      notify.success(t('fee.dialog.done'))
      void queryClient.invalidateQueries({ queryKey: FEES_KEY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(next) => { if (!next) onClose() }}>
      <DialogContent data-testid="fee-credit-note-dialog" data-charge={chargeId}>
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('fee.dialog.creditNoteTitle')}</DialogTitle>
            <DialogDescription>{t('fee.dialog.creditNoteHint')}</DialogDescription>
          </DialogHeader>
          {credit.isError && <LoadFailed error={credit.error} onRetry={() => void credit.refetch()} />}
          {credit.isSuccess && !data && (
            <p className="text-destructive text-sm" role="alert" data-testid="fee-credit-note-missing">
              {t('fee.dialog.creditNoteMissing')}
            </p>
          )}
          {data && (
            <>
              <DescriptionList columns={2} items={[
                { key: 'sponsor', label: t('fee.sponsor'), value: data.company ?? data.sponsorId },
                { key: 'period', label: t('fee.period'), value: data.period },
                { key: 'total', label: t('fee.total'), value: money(data.total) },
                { key: 'credited', label: t('fee.dialog.credited'),
                  value: <span data-testid="fee-credit-note-credited">{money(data.credited)}</span> },
                { key: 'creditable', label: t('fee.dialog.creditable'),
                  value: <span className="font-semibold" data-testid="fee-credit-note-creditable">{money(data.creditable)}</span> },
                { key: 'taxLeft', label: t('fee.dialog.taxLeft'),
                  value: <span data-testid="fee-credit-note-tax-left">{money(data.taxLeft)}</span> },
                ...(data.invoiceNo ? [{ key: 'invoice', label: t('fee.invoice'), value: <code className="font-mono">{data.invoiceNo}</code> }] : []),
              ]} />
              {voided && (
                <Alert variant="destructive" data-testid="fee-credit-note-voided">
                  <AlertDescription>{t('fee.dialog.creditNoteVoided', { invoiceNo: data.invoiceNo ?? '' })}</AlertDescription>
                </Alert>
              )}
              {!voided && creditable <= 0 && (
                <p className="text-muted-foreground text-sm" data-testid="fee-credit-note-nothing-left">
                  {t('fee.dialog.creditNoteNothingLeft')}
                </p>
              )}
            </>
          )}
          {open && (
            <>
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-amount`}>{t('fee.dialog.amount')}</Label>
                <Input id={`${id}-amount`} value={amount} inputMode="numeric" autoComplete="off" required maxLength={17}
                  aria-invalid={invalid ? true : undefined}
                  aria-describedby={`${id}-amount-hint${invalid ? ` ${id}-amount-error` : ''}`}
                  data-testid="fee-credit-note-amount" onChange={(event) => {
                    setAmount(event.target.value)
                    setInvalid(false)
                  }} />
                <p id={`${id}-amount-hint`} className="text-muted-foreground text-sm">
                  {t('fee.dialog.creditNoteAmountHint', { max: money(data.creditable) })}
                </p>
                {invalid && (
                  <p id={`${id}-amount-error`} className="text-destructive text-sm">
                    {t('fee.dialog.creditNoteInvalid', { max: money(data.creditable) })}
                  </p>
                )}
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-reason`}>{t('fee.dialog.reason')}</Label>
                <Textarea id={`${id}-reason`} value={reason} required maxLength={500} aria-describedby={`${id}-reason-hint`}
                  data-testid="fee-credit-note-reason-input" onChange={(event) => setReason(event.target.value)} />
                <p id={`${id}-reason-hint`} className="text-muted-foreground text-sm">{t('fee.dialog.reasonHint')}</p>
              </div>
            </>
          )}
          {problem && (
            <Alert variant="destructive" data-testid="fee-credit-note-problem"><AlertDescription>{problem}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('fee.dialog.cancel')}</Button>
            <Button type="submit" disabled={busy || !complete} aria-busy={busy || undefined}
              data-testid="fee-credit-note-submit">
              {t('fee.dialog.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
