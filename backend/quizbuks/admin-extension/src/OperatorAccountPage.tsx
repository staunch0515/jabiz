import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  type ColumnDef,
} from '@jabiz/admin'
import {
  ACCOUNT_KEY,
  ACCOUNT_TYPES,
  cancelWithdrawal,
  currentMonthInJapan,
  laterChanges,
  percent,
  planInEffect,
  readAccount,
  readOwnWithdrawals,
  readPlans,
  requestWithdrawal,
  saveBankAccount,
  type BankAccountInput,
  type OperatorAccount,
  type OwnWithdrawal,
} from './account-api'
import { EmptyState, LoadFailed, Section, StatusBadge } from './components'
import { useLabels, usePaged, usePoints, when } from './hooks'
import { DICTIONARY, PATH } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { requestedAt: 'requestedAt', amount: 'amount', status: 'status' } as const

/** What a failed call says, as the server words it. */
const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/** The balance and what made it: credited by the settlements, taken by the withdrawals. */
function Balance({ account }: { account: OperatorAccount }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const points = usePoints()
  return (
    <Section title={t('account.balance')} testId="account-balance">
      <p className="text-2xl font-semibold tabular-nums" data-testid="account-balance-value">{points(account.balance)}</p>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t('account.credited')}</dt>
        <dd className="tabular-nums" data-testid="account-credited">{points(account.credited)}</dd>
        <dt className="text-muted-foreground">{t('account.withdrawn')}</dt>
        <dd className="tabular-nums" data-testid="account-withdrawn">{points(account.withdrawn)}</dd>
      </dl>
      <p className="text-muted-foreground text-sm">
        {t('account.creditedHint')}{' '}
        <Link to={PATH.operatorSettlements} className={linkClass}>{t('account.settlements')}</Link>
      </p>
    </Section>
  )
}

/** The plan in effect this month (Japan's), any later change, and the five plans' terms. */
function Plans() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const points = usePoints()
  const planLabel = useLabels(DICTIONARY.operatorPlan)
  const statusLabel = useLabels(DICTIONARY.operatorPlanStatus)
  const plans = useQuery({ queryKey: [...ACCOUNT_KEY, 'plans'], queryFn: readPlans })
  if (plans.isError) {
    return (
      <Section title={t('account.plan')} testId="account-plan">
        <LoadFailed error={plans.error} onRetry={() => void plans.refetch()} />
      </Section>
    )
  }
  if (!plans.data) return <Section title={t('account.plan')} testId="account-plan"><PageState kind="loading" /></Section>
  const month = currentMonthInJapan()
  const current = planInEffect(plans.data.changes, month)
  const terms = plans.data.terms.find((term) => term.plan === current)
  const later = laterChanges(plans.data.changes, month)
  return (
    <Section title={t('account.plan')} testId="account-plan">
      <p data-testid="account-plan-current" data-plan={current}>
        {t('account.planCurrent', { month, plan: planLabel(current) })}
        {terms && ` ${t('account.planTerms', { fee: points(terms.fixedFee), share: percent(terms.platformShare) })}`}
      </p>
      {later.length > 0 && (
        <ul className="flex flex-col gap-1 text-sm" data-testid="account-plan-later">
          {later.map((change) => (
            <li key={`${change.effectiveMonth}-${change.plan}-${change.proposedAt ?? ''}`} className="flex flex-wrap items-center gap-2">
              {t('account.planLater', { month: change.effectiveMonth, plan: planLabel(change.plan) })}
              <StatusBadge code={change.status} label={statusLabel(change.status)} />
            </li>
          ))}
        </ul>
      )}
      <table className="w-full max-w-xl text-sm" data-testid="account-plan-terms">
        <caption className="text-muted-foreground mb-2 text-left">{t('account.planTable')}</caption>
        <thead>
          <tr className="border-b">
            <th scope="col" className="py-1 text-left font-medium">{t('account.planName')}</th>
            <th scope="col" className="py-1 text-right font-medium">{t('account.fixedFee')}</th>
            <th scope="col" className="py-1 text-right font-medium">{t('account.platformShare')}</th>
          </tr>
        </thead>
        <tbody>
          {plans.data.terms.map((term) => (
            <tr key={term.plan} className="border-b last:border-0" aria-current={term.plan === current || undefined}
              data-testid="plan-terms-row">
              <th scope="row" className="py-1 text-left font-normal">
                {planLabel(term.plan)}
                {term.plan === current && <Badge variant="secondary" className="ml-2">{t('account.inEffect')}</Badge>}
              </th>
              <td className="py-1 text-right tabular-nums">{points(term.fixedFee)}</td>
              <td className="py-1 text-right tabular-nums">{percent(term.platformShare)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Section>
  )
}

const EMPTY_BANK: BankAccountInput = { bankCode: '', bankName: '', branchCode: '', branchName: '',
  accountType: 'ORDINARY', accountNumber: '', accountHolder: '' }

/** Whether the form is complete in the Zengin format (the server checks it again, the holder's katakana too). */
function bankComplete(v: BankAccountInput): boolean {
  return /^\d{4}$/.test(v.bankCode) && /^\d{3}$/.test(v.branchCode) && /^\d{1,7}$/.test(v.accountNumber)
    && v.bankName.trim() !== '' && v.branchName.trim() !== '' && v.accountHolder.trim() !== ''
}

/** The bank account withdrawals go to (the number masked), and for the lead a form to set it. */
function BankAccount({ account }: { account: OperatorAccount }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const typeLabel = useLabels(DICTIONARY.bankAccountType)
  const [values, setValues] = useState<BankAccountInput>(EMPTY_BANK)
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const set = (field: keyof BankAccountInput) => (value: string) =>
    setValues((current) => ({ ...current, [field]: value }))
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !bankComplete(values)) return
    setBusy(true)
    setProblem(null)
    try {
      const saved = await saveBankAccount({ ...values, bankName: values.bankName.trim(),
        branchName: values.branchName.trim(), accountHolder: values.accountHolder.trim() })
      notify.success(saved.usableFrom ? t('account.bankSavedFrom', { at: when(saved.usableFrom) }) : t('account.bankSaved'))
      setValues(EMPTY_BANK)
      setEditing(false)
      await queryClient.invalidateQueries({ queryKey: ACCOUNT_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  const field = (name: keyof BankAccountInput, props: { maxLength: number; numeric?: boolean; className?: string }) => (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`${id}-${name}`}>{t(`account.bankFields.${name}`)}</Label>
      <Input id={`${id}-${name}`} value={values[name]} required autoComplete="off" maxLength={props.maxLength}
        inputMode={props.numeric ? 'numeric' : undefined} className={props.className}
        data-testid={`bank-${name}`} onChange={(event) => set(name)(event.target.value)} />
    </div>
  )
  const bank = account.bank
  return (
    <Section title={t('account.bank')} testId="account-bank" actions={account.lead && !editing ? (
      <Button size="sm" variant="outline" data-testid="bank-edit" onClick={() => setEditing(true)}>
        {t(bank ? 'account.bankChange' : 'account.bankSet')}
      </Button>
    ) : undefined}>
      {bank ? (
        <p data-testid="account-bank-current">
          {bank.bankName} {bank.branchName} · {typeLabel(bank.accountType)} {bank.accountNumber} · {bank.accountHolder}
          {bank.changedAt && <span className="text-muted-foreground"> · {t('account.bankChanged', { at: when(bank.changedAt) })}</span>}
        </p>
      ) : <p className="text-muted-foreground" data-testid="account-bank-none">{t('account.bankNone')}</p>}
      {account.lead && editing && (
        <form noValidate className="flex flex-col gap-4" data-testid="bank-form" onSubmit={(event) => void submit(event)}>
          <p className="text-muted-foreground text-sm">
            {t('account.bankHint', { hours: account.cooldownHours ?? '0' })}
          </p>
          <div className="flex flex-wrap gap-4">
            {field('bankCode', { maxLength: 4, numeric: true, className: 'w-28' })}
            {field('bankName', { maxLength: 60, className: 'w-64' })}
          </div>
          <div className="flex flex-wrap gap-4">
            {field('branchCode', { maxLength: 3, numeric: true, className: 'w-28' })}
            {field('branchName', { maxLength: 60, className: 'w-64' })}
          </div>
          <div className="flex flex-wrap gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-accountType`}>{t('account.bankFields.accountType')}</Label>
              <Select value={values.accountType} onValueChange={set('accountType')}>
                <SelectTrigger id={`${id}-accountType`} className="w-40" data-testid="bank-accountType"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {ACCOUNT_TYPES.map((value) => <SelectItem key={value} value={value}>{typeLabel(value)}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            {field('accountNumber', { maxLength: 7, numeric: true, className: 'w-36' })}
            {field('accountHolder', { maxLength: 60, className: 'w-64' })}
          </div>
          {problem && <Alert variant="destructive" data-testid="bank-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={busy || !bankComplete(values)} aria-busy={busy || undefined}
              data-testid="bank-save">{t('account.bankSave')}</Button>
            <Button type="button" variant="outline" onClick={() => { setEditing(false); setProblem(null) }}>
              {t('account.cancel')}
            </Button>
          </div>
        </form>
      )}
    </Section>
  )
}

/** The lead's withdrawal of a whole amount, between the least one may take and the balance (the server decides). */
function WithdrawalForm({ account }: { account: OperatorAccount }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const points = usePoints()
  const [amount, setAmount] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const whole = /^[1-9]\d{0,14}$/.test(amount.trim())
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !whole) return
    setBusy(true)
    setProblem(null)
    try {
      await requestWithdrawal(Number(amount.trim()))
      notify.success(t('account.requested'))
      setAmount('')
      await queryClient.invalidateQueries({ queryKey: ACCOUNT_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form noValidate className="flex flex-col gap-2" data-testid="withdraw-form" onSubmit={(event) => void submit(event)}>
      <Label htmlFor={`${id}-amount`}>{t('account.amount')}</Label>
      <div className="flex flex-wrap items-center gap-2">
        <Input id={`${id}-amount`} value={amount} inputMode="numeric" autoComplete="off" maxLength={15} className="w-48"
          aria-describedby={`${id}-amount-hint`} data-testid="withdraw-amount"
          onChange={(event) => setAmount(event.target.value)} />
        <Button type="submit" disabled={busy || !whole} aria-busy={busy || undefined} data-testid="withdraw-submit">
          {t('account.withdraw')}
        </Button>
      </div>
      <p id={`${id}-amount-hint`} className="text-muted-foreground text-sm" data-testid="withdraw-hint">
        {t('account.amountHint', { minimum: points(account.minimum ?? '0'), balance: points(account.balance) })}
      </p>
      {problem && <Alert variant="destructive" data-testid="withdraw-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
    </form>
  )
}

/** The withdrawals, the newest first: the lead cancels one not yet paid; a rejection says why. */
function Withdrawals({ account }: { account: OperatorAccount }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  const points = usePoints()
  const statusLabel = useLabels(DICTIONARY.operatorWithdrawalStatus)
  const [busy, setBusy] = useState(false)
  const { query, table, sortable } = usePaged([...ACCOUNT_KEY, 'withdrawals'], readOwnWithdrawals, true, SORTS)
  const lead = account.lead
  const columns = useMemo<ColumnDef<OwnWithdrawal, unknown>[]>(() => [
    { id: 'requestedAt', header: t('account.requestedAt'), cell: ({ row }) => when(row.original.requestedAt) },
    { id: 'amount', header: t('account.amount'), meta: right, cell: ({ row }) => points(row.original.amount) },
    {
      id: 'status',
      header: t('account.status'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <StatusBadge code={row.original.status} label={statusLabel(row.original.status)} className="w-fit" />
          {row.original.rejectReason && (
            <span className="text-muted-foreground text-sm" data-testid="withdrawal-reason">
              {t('account.rejectReason', { reason: row.original.rejectReason })}
            </span>
          )}
        </span>
      ),
    },
    { id: 'to', header: t('account.to'),
      cell: ({ row }) => [row.original.bankName, row.original.accountNumber].filter(Boolean).join(' ') },
    { id: 'paidOn', header: t('account.paidOn'), cell: ({ row }) => row.original.paidOn ?? '' },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('account.cancelWithdrawal')}</span>,
      cell: ({ row }) => (lead && row.original.status === 'REQUESTED' ? (
        <span className="flex justify-end">
          <Button size="sm" variant="outline" disabled={busy} data-testid="withdrawal-cancel"
            onClick={() => {
              setBusy(true)
              void cancelWithdrawal(row.original.withdrawalId).then(async () => {
                notify.success(t('account.cancelled'))
                await queryClient.invalidateQueries({ queryKey: ACCOUNT_KEY })
              }).catch((e: unknown) => notify.error(problemOf(e))).finally(() => setBusy(false))
            }}>
            {t('account.cancelWithdrawal')}<span className="sr-only">: {points(row.original.amount)}</span>
          </Button>
        </span>
      ) : null),
    },
  ], [t, points, statusLabel, lead, busy, queryClient])
  return (
    <Section title={t('account.withdrawals')} testId="account-withdrawals">
      <p className="text-muted-foreground text-sm">{t(lead ? 'account.withdrawalsHint' : 'account.withdrawalsLeadOnly')}</p>
      {lead && <WithdrawalForm account={account} />}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('account.withdrawals')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.withdrawalId} empty={<EmptyState art="money" title={t('account.none')} />}
            rowProps={(row) => ({ 'data-testid': 'withdrawal-row', 'data-id': row.withdrawalId, 'data-status': row.status })} />
        )}
    </Section>
  )
}

/**
 * The operator's account (Q48e-2): money stays with the platform. Each month's settlement is credited here; the
 * operator's lead sets the bank account and withdraws to it (both with a recent second factor, which the platform
 * asks for); finance pays and another administrator approves. Also the plan in effect and the five plans' terms.
 * Amounts are points, never a currency, as the apps show them.
 */
export default function OperatorAccountPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const account = useQuery({ queryKey: [...ACCOUNT_KEY, 'account'], queryFn: readAccount })
  const header = <PageHeader title={t('account.title')} description={t('account.description')} className="pb-0" />
  if (account.isPending) return <div className="flex flex-col gap-4">{header}<PageState kind="loading" /></div>
  if (account.isError) {
    return <div className="flex flex-col gap-4">{header}<LoadFailed error={account.error} onRetry={() => void account.refetch()} /></div>
  }
  if (!account.data) {
    return <div className="flex flex-col gap-4">{header}<PageState kind="notFound" description={t('account.notMember')} /></div>
  }
  return (
    <div className="flex flex-col gap-4" data-testid="operator-account" data-lead={account.data.lead}>
      {header}
      <Balance account={account.data} />
      <Plans />
      <BankAccount account={account.data} />
      <Withdrawals account={account.data} />
    </div>
  )
}
