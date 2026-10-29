import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  DataTable,
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
  PageHeader,
  RadioGroup,
  RadioGroupItem,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { todayInJapan } from './finance-api'
import { parseAmount } from './finance'
import { StatusFilter } from './finance-components'
import { LoadFailed } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { BANK_REVIEW, DICTIONARY, PATH, PERMISSION } from './ids'
import { MANUAL_KINDS, readApprovedSponsors, readManualCredits, recordManualCredit, SPONSOR_CHOICES,
  type ManualCredit, type ManualKind } from './manual-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { createdAt: 'createdAt', amount: 'amount', status: 'status' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const KEY = ['qb-ext', 'finance', 'manual-credits'] as const
/** The states a manual credit can be in (backend `ManualCredits`): under review, then paid or cancelled. */
const MANUAL_STATUSES = [BANK_REVIEW, 'PAID', 'CANCELLED'] as const

/**
 * Records a manual credit (`QB_TOPUP_MANUAL_RECORD`): whose wallet, how much, which kind and why; money received
 * outside the system also the day and the statement's reference. Nothing is credited here: the server asks another
 * administrator's approval, and refuses a statement entry recorded before, a day in the future, a sponsor not
 * approved or one's own wallet. Its refusals are shown as it words them.
 */
function RecordDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const sponsors = useQuery({ queryKey: [...KEY, 'sponsors'], queryFn: readApprovedSponsors })
  const [sponsorId, setSponsorId] = useState('')
  const [typedId, setTypedId] = useState('')
  // The list holds the first SPONSOR_CHOICES by company; beyond it, the sponsor's id from the users page does.
  const capped = (sponsors.data?.length ?? 0) >= SPONSOR_CHOICES
  const [kind, setKind] = useState<ManualKind>('PAID_OUTSIDE')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [receivedOn, setReceivedOn] = useState(todayInJapan())
  const [reference, setReference] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const outside = kind === 'PAID_OUTSIDE'
  const chosen = sponsorId || typedId.trim()
  const complete = !!chosen && !!reason.trim() && (!outside || (!!reference.trim() && !!receivedOn))
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const value = parseAmount(amount)
    setInvalid(value === null)
    if (value === null || busy || !complete) return
    setBusy(true)
    setProblem(null)
    try {
      await recordManualCredit({ sponsorId: chosen, kind, amount: value, reason: reason.trim(),
        ...(outside ? { receivedOn, bankReference: reference.trim() } : {}) })
      notify.success(t('manual.dialog.done'))
      void queryClient.invalidateQueries({ queryKey: KEY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="manual-record-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('manual.dialog.title')}</DialogTitle>
            <DialogDescription>{t('manual.dialog.hint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-sponsor`}>{t('manual.dialog.sponsor')}</Label>
            {sponsors.isError
              ? <LoadFailed error={sponsors.error} onRetry={() => void sponsors.refetch()} />
              : sponsors.data && sponsors.data.length === 0
                ? <p className="text-muted-foreground text-sm" data-testid="manual-no-sponsors">{t('manual.dialog.noSponsors')}</p>
                : (
                  <Select value={sponsorId} onValueChange={setSponsorId} disabled={!sponsors.data}>
                    <SelectTrigger id={`${id}-sponsor`} aria-describedby={`${id}-sponsor-hint`}
                      data-testid="manual-record-sponsor">
                      <SelectValue placeholder={t('manual.dialog.sponsorPlaceholder')} />
                    </SelectTrigger>
                    <SelectContent>
                      {(sponsors.data ?? []).map((sponsor) => (
                        <SelectItem key={sponsor.userId} value={sponsor.userId}>
                          {sponsor.company ?? sponsor.userId}{sponsor.contactName ? ` · ${sponsor.contactName}` : ''}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
            <p id={`${id}-sponsor-hint`} className="text-muted-foreground text-sm">{t('manual.dialog.sponsorHint')}</p>
            {capped && (
              <>
                <Label htmlFor={`${id}-sponsor-id`}>{t('manual.dialog.sponsorId')}</Label>
                <Input id={`${id}-sponsor-id`} value={typedId} autoComplete="off" maxLength={128}
                  aria-describedby={`${id}-sponsor-id-hint`} data-testid="manual-record-sponsor-id"
                  onChange={(event) => {
                    setTypedId(event.target.value)
                    if (event.target.value.trim()) setSponsorId('')
                  }} />
                <p id={`${id}-sponsor-id-hint`} className="text-muted-foreground text-sm" data-testid="manual-sponsor-capped">
                  {t('manual.dialog.sponsorIdHint', { count: SPONSOR_CHOICES })}
                </p>
              </>
            )}
          </div>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('manual.dialog.kind')}</legend>
            <RadioGroup value={kind} onValueChange={(value) => setKind(value as ManualKind)}
              data-testid="manual-record-kind">
              {MANUAL_KINDS.map((value) => (
                <div key={value} className="flex items-start gap-2">
                  <RadioGroupItem id={`${id}-kind-${value}`} value={value} className="mt-1"
                    aria-describedby={`${id}-kind-${value}-hint`} data-testid={`manual-record-kind-${value}`} />
                  <div className="flex flex-col">
                    <Label htmlFor={`${id}-kind-${value}`}>{t(`manual.kinds.${value}`)}</Label>
                    <span id={`${id}-kind-${value}-hint`} className="text-muted-foreground text-sm">
                      {t(`manual.dialog.kindHints.${value}`)}
                    </span>
                  </div>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-amount`}>{t('manual.dialog.amount')}</Label>
            <Input id={`${id}-amount`} value={amount} inputMode="numeric" autoComplete="off" required maxLength={17}
              aria-invalid={invalid ? true : undefined}
              aria-describedby={`${id}-amount-hint${invalid ? ` ${id}-amount-error` : ''}`}
              data-testid="manual-record-amount" onChange={(event) => {
                setAmount(event.target.value)
                setInvalid(false)
              }} />
            <p id={`${id}-amount-hint`} className="text-muted-foreground text-sm">{t('manual.dialog.amountHint')}</p>
            {invalid && <p id={`${id}-amount-error`} className="text-destructive text-sm">{t('manual.dialog.invalid')}</p>}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-reason`}>{t('manual.dialog.reason')}</Label>
            <Textarea id={`${id}-reason`} value={reason} required maxLength={500} aria-describedby={`${id}-reason-hint`}
              data-testid="manual-record-reason" onChange={(event) => setReason(event.target.value)} />
            <p id={`${id}-reason-hint`} className="text-muted-foreground text-sm">{t('manual.dialog.reasonHint')}</p>
          </div>
          {outside && (
            <>
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-day`}>{t('manual.dialog.receivedOn')}</Label>
                <Input id={`${id}-day`} type="date" className="w-fit" value={receivedOn} max={todayInJapan()} required
                  data-testid="manual-record-day" onChange={(event) => setReceivedOn(event.target.value)} />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-reference`}>{t('manual.dialog.reference')}</Label>
                <Input id={`${id}-reference`} value={reference} required maxLength={64} autoComplete="off"
                  aria-describedby={`${id}-reference-hint`} data-testid="manual-record-reference"
                  onChange={(event) => setReference(event.target.value)} />
                <p id={`${id}-reference-hint`} className="text-muted-foreground text-sm">
                  {t('manual.dialog.referenceHint')}
                </p>
              </div>
            </>
          )}
          {problem && (
            <Alert variant="destructive" data-testid="manual-record-problem"><AlertDescription>{problem}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('manual.dialog.cancel')}</Button>
            <Button type="submit" disabled={busy || !complete} aria-busy={busy || undefined}
              data-testid="manual-record-submit">
              {t('manual.dialog.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The manual credits (docs/quizbuks/plans/Q18-bank-settings-credit.md, Q18b), newest first, by state: finance records
 * one for another administrator's approval; one under review opens its page, where that administrator decides.
 * Credits happen only on the server, after the approval.
 */
export default function ManualCreditsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const status = useLabels(DICTIONARY.topUpStatus)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const [recording, setRecording] = useState(false)
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const { query, table, sortable } = usePaged([...KEY, filterStatus],
    (offset, limit, sorts) => readManualCredits(filterStatus, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<ManualCredit, unknown>[]>(() => [
    { id: 'createdAt', header: t('manual.createdAt'), cell: ({ row }) => when(row.original.createdAt) },
    { id: 'code', header: t('manual.code'),
      cell: ({ row }) => <code className="font-mono" data-testid="manual-row-code">{row.original.transferCode}</code> },
    {
      id: 'sponsor',
      header: t('manual.sponsor'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.sponsorId)} className={linkClass}>
          {row.original.company ?? <UserName id={row.original.sponsorId} />}
        </Link>
      ),
    },
    { id: 'kind', header: t('manual.kind'),
      cell: ({ row }) => <span data-testid="manual-row-kind">{t(`manual.kinds.${row.original.manualKind}`)}</span> },
    { id: 'amount', header: t('manual.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'reason', header: t('manual.reason'), cell: ({ row }) => row.original.manualReason },
    {
      id: 'status',
      header: t('manual.status'),
      cell: ({ row }) => <Badge variant="outline" data-testid="manual-row-status">{status(row.original.status)}</Badge>,
    },
    { id: 'recordedBy', header: t('manual.recordedBy'),
      cell: ({ row }) => (row.original.recordedBy ? <UserName id={row.original.recordedBy} /> : '') },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('manual.open')}</span>,
      cell: ({ row }) => (
        <span className="flex justify-end">
          <Button size="sm" variant={row.original.status === BANK_REVIEW ? 'default' : 'outline'} asChild>
            <Link to={PATH.manualCredit(row.original.topUpId)} data-testid="manual-row-open">
              {row.original.status === BANK_REVIEW ? t('manual.review') : t('manual.open')}
              <span className="sr-only">: {row.original.transferCode}</span>
            </Link>
          </Button>
        </span>
      ),
    },
  ], [t, status])

  return (
    <div className="flex flex-col gap-4" data-testid="manual-credits">
      <PageHeader title={t('manual.title')} description={t('manual.description')} className="pb-0"
        actions={can(PERMISSION.manualRecord) && (
          <Button onClick={() => setRecording(true)} data-testid="manual-new">{t('manual.new')}</Button>
        )} />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.topUpStatus} value={filterStatus} testId="manual-status"
          only={MANUAL_STATUSES} onChange={(value) => set({ status: value })} />
        {filterStatus && (
          <Button variant="ghost" size="sm" onClick={() => set({ status: null })}>{t('manual.all')}</Button>
        )}
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('manual.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.topUpId}
            empty={t('manual.none')}
            rowProps={(row) => ({ 'data-testid': 'manual-row', 'data-id': row.topUpId, 'data-status': row.status })} />
        )}
      {recording && <RecordDialog onClose={() => setRecording(false)} />}
    </div>
  )
}
