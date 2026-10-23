import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  DescriptionList,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  PageState,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import { Link } from '../qb/router'
import {
  BANK_WAITING,
  cancelBankTopUp,
  readBankAccount,
  readTopUp,
  reportBankTransfer,
  todayInJapan,
  WALLET_PATH,
  WALLET_QUERY,
  type BankAccount,
  type TopUp,
} from './api'
import './messages'
import { STATUS_BADGES, useTopUpStatusLabel } from './WalletPage'

/**
 * Tells finance the money was sent (optional: finance finds it by the code anyway): the payer's name as the bank
 * shows it, the code before it, and the day. A report may be corrected until the money is recorded.
 */
function ReportForm({ topUp }: { topUp: TopUp }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [payerName, setPayerName] = useState(() => topUp.payerName ?? `${topUp.transferCode ?? ''} `)
  const [transferDate, setTransferDate] = useState(() => topUp.transferDate ?? todayInJapan())
  const report = useMutation({
    mutationFn: () => reportBankTransfer(topUp.topUpId, payerName.trim(), transferDate),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: WALLET_QUERY }),
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!report.isPending) report.mutate()
  }
  const nameProblem = fieldProblem(report.error, 'payerName')
  const dateProblem = fieldProblem(report.error, 'transferDate')
  const general = generalProblem(report.error, ['payerName', 'transferDate'])
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4" aria-labelledby={`${id}-title`}
      data-testid="bank-report">
      <h2 id={`${id}-title`} className="text-lg font-semibold">{t('wallet.bank.reportTitle')}</h2>
      <p className="text-muted-foreground text-sm">{t('wallet.bank.reportHint')}</p>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`}>{t('wallet.bank.payerName')}</Label>
        <Input id={`${id}-name`} value={payerName} required maxLength={48} autoComplete="off"
          aria-invalid={nameProblem ? true : undefined}
          aria-describedby={`${id}-name-hint${nameProblem ? ` ${id}-name-error` : ''}`}
          data-testid="bank-report-name" onChange={(event) => {
            setPayerName(event.target.value)
            report.reset()
          }} />
        <p id={`${id}-name-hint`} className="text-muted-foreground text-sm">
          {t('wallet.bank.payerNameHint', { code: topUp.transferCode })}
        </p>
        {nameProblem && <p id={`${id}-name-error`} className="text-destructive text-sm">{nameProblem}</p>}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-date`}>{t('wallet.bank.transferDate')}</Label>
        <Input id={`${id}-date`} type="date" value={transferDate} required max={todayInJapan()}
          className="w-fit" aria-invalid={dateProblem ? true : undefined}
          aria-describedby={dateProblem ? `${id}-date-error` : undefined} data-testid="bank-report-date"
          onChange={(event) => {
            setTransferDate(event.target.value)
            report.reset()
          }} />
        {dateProblem && <p id={`${id}-date-error`} className="text-destructive text-sm">{dateProblem}</p>}
      </div>
      {general && (
        <Alert variant="destructive" data-testid="bank-report-problem"><AlertDescription>{general}</AlertDescription></Alert>
      )}
      {report.isSuccess && (
        <p role="status" className="text-sm" data-testid="bank-report-done">{t('wallet.bank.reportDone')}</p>
      )}
      <Button type="submit" className="w-fit" disabled={report.isPending} aria-busy={report.isPending || undefined}
        data-testid="bank-report-submit">
        {topUp.status === 'REPORTED' ? t('wallet.bank.reportAgain') : t('wallet.bank.reportSubmit')}
      </Button>
    </form>
  )
}

/** Withdraws the request, after asking: a transfer already sent is still credited when it arrives. */
function CancelButton({ topUp }: { topUp: TopUp }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const cancel = useMutation({
    mutationFn: () => cancelBankTopUp(topUp.topUpId),
    onSuccess: () => {
      setOpen(false)
      void queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    },
  })
  return (
    <>
      <Button variant="outline" className="w-fit" onClick={() => setOpen(true)} data-testid="bank-cancel">
        {t('wallet.bank.cancel')}
      </Button>
      <Dialog open={open} onOpenChange={(next) => {
        setOpen(next)
        if (!next) cancel.reset()
      }}>
        <DialogContent data-testid="bank-cancel-dialog">
          <DialogHeader>
            <DialogTitle>{t('wallet.bank.cancelTitle')}</DialogTitle>
            <DialogDescription>{t('wallet.bank.cancelHint')}</DialogDescription>
          </DialogHeader>
          {cancel.isError && (
            <Alert variant="destructive"><AlertDescription>{problemText(cancel.error)}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>{t('wallet.bank.keep')}</Button>
            <Button variant="destructive" disabled={cancel.isPending} aria-busy={cancel.isPending || undefined}
              onClick={() => cancel.mutate()} data-testid="bank-cancel-confirm">
              {t('wallet.bank.cancelConfirm')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

/** The account to transfer to, as finance set it (`qb.bank.receiving.*`). */
function AccountDetails({ account }: { account: BankAccount }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <section aria-labelledby="bank-account-title" className="flex flex-col gap-2">
      <h2 id="bank-account-title" className="text-lg font-semibold">{t('wallet.bank.accountTitle')}</h2>
      <DescriptionList data-testid="bank-account" items={[
        { key: 'bank', label: t('wallet.bank.bank'), value: account.bank ?? '—' },
        { key: 'branch', label: t('wallet.bank.branch'), value: account.branch ?? '—' },
        { key: 'type', label: t('wallet.bank.accountType'), value: account.accountType ?? '—' },
        { key: 'number', label: t('wallet.bank.accountNumber'),
          value: <span className="font-mono" data-testid="bank-account-number">{account.accountNumber ?? '—'}</span> },
        { key: 'holder', label: t('wallet.bank.accountHolder'), value: account.accountHolder ?? '—' },
      ]} />
    </section>
  )
}

/**
 * A top-up by bank transfer (docs/quizbuks/plans/Q13-bank-transfer.md, Q13a): what to transfer where, with the code
 * to put before the payer's name; the report that it was sent; and its state. The wallet is credited only when finance
 * has found the money on the statement and another administrator approved it: this page only shows what the server
 * recorded, and never credits anything.
 */
export function BankTopUpPage({ topUpId }: { topUpId: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const statusLabel = useTopUpStatusLabel()
  const topUp = useQuery({ queryKey: [...WALLET_QUERY, 'bank', topUpId], queryFn: () => readTopUp(topUpId) })
  const account = useQuery({ queryKey: [...WALLET_QUERY, 'bank-account'], queryFn: readBankAccount })

  const backLink = (
    <Button asChild variant="ghost" size="sm" className="w-fit">
      <Link to={WALLET_PATH} data-testid="topup-back">
        <ArrowLeft aria-hidden />
        {t('wallet.detail.back')}
      </Link>
    </Button>
  )

  if (topUp.isPending) return <PageState kind="loading" />
  if (topUp.isError) {
    return (
      <section className="flex flex-col gap-4">
        {backLink}
        <PageState kind="error" titleAs="h1" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void topUp.refetch()}>{tQb('retry')}</Button>} />
      </section>
    )
  }
  const data = topUp.data
  if (data === null || data.method !== 'BANK') {
    return (
      <section className="flex flex-col gap-4">
        {backLink}
        <PageState kind="notFound" titleAs="h1" description={t('wallet.detail.notFound')}
          data-testid="topup-missing" />
      </section>
    )
  }

  const waiting = BANK_WAITING.includes(data.status)
  const amount = formatKudos(data.requestedAmount ?? data.amount)
  const tone: 'default' | 'success' | 'warning' = data.status === 'PAID' ? 'success'
    : data.status === 'AWAITING' ? 'warning' : 'default'
  const message = data.status === 'PAID'
    ? t('wallet.detail.paid', { amount: formatKudos(data.amount) })
    : t(`wallet.bank.states.${data.status}`, { amount, code: data.transferCode })
  // The bank may keep its fee: what arrived is what was credited.
  const short = data.status === 'PAID' && data.requestedAmount !== null && data.requestedAmount !== data.amount

  return (
    <section className="flex max-w-xl flex-col gap-6" aria-labelledby="bank-topup-title" data-testid="bank-topup">
      {backLink}
      <h1 id="bank-topup-title" className="text-xl font-semibold">{t('wallet.bank.title')}</h1>
      <Alert variant={tone} role="status" data-testid="bank-topup-state" data-state={data.status}>
        <AlertDescription>
          <span>{message}</span>
          {short && <span className="block">{t('wallet.bank.short', { requested: amount })}</span>}
        </AlertDescription>
      </Alert>
      <DescriptionList data-testid="bank-topup-details" items={[
        { key: 'code', label: t('wallet.bank.code'),
          value: <span className="font-mono text-lg font-semibold" data-testid="bank-topup-code">
            {data.transferCode ?? '—'}</span> },
        { key: 'amount', label: t('wallet.bank.amount'),
          value: <span data-testid="bank-topup-amount">{amount}</span> },
        { key: 'status', label: t('wallet.detail.status'),
          value: (
            <Badge variant={STATUS_BADGES[data.status] ?? 'outline'} data-testid="topup-status"
              data-status={data.status}>
              {statusLabel(data.status)}
            </Badge>
          ) },
        { key: 'created', label: t('wallet.detail.created'), value: formatDateTime(data.createdAt) },
        ...(data.payerName ? [{ key: 'payer', label: t('wallet.bank.payerName'), value: data.payerName }] : []),
        ...(data.transferDate ? [{ key: 'sent', label: t('wallet.bank.transferDate'), value: data.transferDate }]
          : []),
        ...(data.status === 'PAID' ? [{ key: 'credited', label: t('wallet.bank.credited'),
          value: formatKudos(data.amount) }] : []),
        ...(data.receivedOn ? [{ key: 'received', label: t('wallet.bank.receivedOn'), value: data.receivedOn }]
          : []),
        ...(data.paidAt ? [{ key: 'paidAt', label: t('wallet.detail.paidAt'), value: formatDateTime(data.paidAt) }]
          : []),
      ]} />
      {waiting && account.data && <AccountDetails account={account.data} />}
      {waiting && <ReportForm topUp={data} />}
      {waiting && <CancelButton topUp={data} />}
    </section>
  )
}
