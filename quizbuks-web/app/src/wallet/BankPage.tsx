import {
  Alert,
  AlertDescription,
  Button,
  Input,
  Label,
  PageState,
  RadioGroup,
  RadioGroupItem,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import {
  ACCOUNT_TYPES,
  BANK_QUERY,
  netOf,
  readBankSettings,
  readWallet,
  requestWithdrawal,
  saveBankAccount,
  setPayoutMethod,
  transferable,
  usableFrom,
  WALLET_PATH,
  WALLET_QUERY,
  type AccountType,
  type BankAccountForm,
  type BankSettings,
  type PayoutMethod,
} from './api'
import './messages'

const METHODS: PayoutMethod[] = ['STRIPE', 'BANK']

/**
 * Withdrawals to a bank account (Q13b; docs/quizbuks/plans/Q13-bank-transfer.md section 3.3): how the taker is paid
 * (the daily Stripe transfer or withdrawals they ask for), their Japanese bank account (the number shown masked; a
 * change starts a cooling-off period and is mailed to them), and a withdrawal (the fixed fee and what they receive).
 * The server checks everything again; each withdrawal waits for a person's review before finance pays it.
 */
export function BankPage() {
  const { t } = useTranslation(APP)
  const settings = useQuery({ queryKey: BANK_QUERY, queryFn: readBankSettings })
  const back = (
    <Button asChild variant="ghost" size="sm" className="w-fit">
      <Link to={WALLET_PATH} data-testid="bank-back">
        <ArrowLeft aria-hidden />
        {t('bank.back')}
      </Link>
    </Button>
  )
  if (settings.isPending) return <PageState kind="loading" />
  return (
    <section className="flex flex-col gap-4" aria-labelledby="bank-title" data-testid="bank">
      {back}
      <h1 id="bank-title" className="text-xl font-semibold">{t('bank.title')}</h1>
      {settings.isError && (
        <Alert variant="destructive"><AlertDescription>{problemText(settings.error)}</AlertDescription></Alert>
      )}
      {settings.isSuccess && !settings.data.enabled && (
        <Alert data-testid="bank-off"><AlertDescription>{t('bank.off')}</AlertDescription></Alert>
      )}
      {settings.isSuccess && settings.data.enabled && (
        <>
          <p className="text-muted-foreground text-sm">{t('bank.intro')}</p>
          <MethodSection settings={settings.data} />
          <AccountSection settings={settings.data} />
          {settings.data.method === 'BANK' && settings.data.account && <WithdrawSection settings={settings.data} />}
        </>
      )}
    </section>
  )
}

function MethodSection({ settings }: { settings: BankSettings }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const queryClient = useQueryClient()
  const change = useMutation({
    mutationFn: setPayoutMethod,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: WALLET_QUERY }),
  })
  return (
    <section aria-labelledby={`${id}-title`} className="flex flex-col gap-2 rounded-lg border p-3">
      <h2 id={`${id}-title`} className="font-semibold">{t('bank.method.title')}</h2>
      <RadioGroup value={settings.method} data-testid="bank-method" aria-labelledby={`${id}-title`}
        disabled={change.isPending} onValueChange={(value) => change.mutate(value as PayoutMethod)}>
        {METHODS.map((value) => (
          <div key={value} className="flex items-start gap-2">
            <RadioGroupItem id={`${id}-${value}`} value={value} data-testid={`bank-method-${value}`}
              aria-describedby={`${id}-${value}-hint`} disabled={value === 'BANK' && !settings.account} />
            <div className="flex flex-col">
              <Label htmlFor={`${id}-${value}`}>{t(`bank.method.${value}`)}</Label>
              <span id={`${id}-${value}-hint`} className="text-muted-foreground text-xs">
                {t(`bank.method.${value}Hint`)}
              </span>
            </div>
          </div>
        ))}
      </RadioGroup>
      {!settings.account && <p className="text-muted-foreground text-xs">{t('bank.method.needsAccount')}</p>}
      {change.isError && (
        <Alert variant="destructive" data-testid="bank-method-problem">
          <AlertDescription>{problemText(change.error)}</AlertDescription>
        </Alert>
      )}
    </section>
  )
}

const EMPTY: BankAccountForm = {
  bankCode: '', bankName: '', branchCode: '', branchName: '', accountType: 'ORDINARY', bankAccountNumber: '',
  accountHolder: '',
}

function AccountSection({ settings }: { settings: BankSettings }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const queryClient = useQueryClient()
  const account = settings.account
  const [editing, setEditing] = useState(!account)
  const [form, setForm] = useState<BankAccountForm>(account ? {
    ...EMPTY, bankCode: account.bankCode, bankName: account.bankName, branchCode: account.branchCode,
    branchName: account.branchName, accountType: account.accountType, accountHolder: account.accountHolder,
  } : EMPTY)
  const [inUse, setInUse] = useState(false)
  // The time the page was opened: the cooling-off note is about then (the server decides at the request).
  const [openedAt] = useState(() => Date.now())
  const save = useMutation({
    mutationFn: saveBankAccount,
    onSuccess: (output) => {
      setInUse(!output.saved)
      if (output.saved) {
        setEditing(false)
        setForm((current) => ({ ...current, bankAccountNumber: '' }))
        void queryClient.invalidateQueries({ queryKey: BANK_QUERY })
      }
    },
  })
  const from = usableFrom(settings)
  const cooling = from !== null && from.getTime() > openedAt
  const field = (name: keyof BankAccountForm, props: { inputMode?: 'numeric'; maxLength: number }) => (
    <div className="flex flex-col gap-1">
      <Label htmlFor={`${id}-${name}`}>{t(`bank.account.${name}`)}</Label>
      <Input id={`${id}-${name}`} value={form[name]} required data-testid={`bank-${name}`}
        aria-describedby={`${id}-${name}-hint`} autoComplete="off" {...props}
        onChange={(event) => setForm({ ...form, [name]: event.target.value })} />
      <span id={`${id}-${name}-hint`} className="text-muted-foreground text-xs">{t(`bank.account.${name}Hint`)}</span>
    </div>
  )
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate(form)
  }
  return (
    <section aria-labelledby={`${id}-title`} className="flex flex-col gap-3 rounded-lg border p-3"
      data-testid="bank-account">
      <h2 id={`${id}-title`} className="font-semibold">{t('bank.account.title')}</h2>
      {account && !editing && (
        <>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm" data-testid="bank-account-saved">
            <dt className="text-muted-foreground">{t('bank.account.bank')}</dt>
            <dd>{account.bankName} ({account.bankCode}) · {account.branchName} ({account.branchCode})</dd>
            <dt className="text-muted-foreground">{t('bank.account.accountType')}</dt>
            <dd>{t(`bank.account.types.${account.accountType}`)}</dd>
            <dt className="text-muted-foreground">{t('bank.account.bankAccountNumber')}</dt>
            <dd data-testid="bank-account-number">{account.accountNumber}</dd>
            <dt className="text-muted-foreground">{t('bank.account.accountHolder')}</dt>
            <dd>{account.accountHolder}</dd>
          </dl>
          {cooling && (
            <p className="text-sm" data-testid="bank-cooling">
              {t('bank.account.cooling', { date: from.toLocaleString() })}
            </p>
          )}
          <Button variant="outline" size="sm" className="self-start" onClick={() => setEditing(true)}
            data-testid="bank-account-edit">
            {t('bank.account.change')}
          </Button>
        </>
      )}
      {editing && (
        <form className="flex flex-col gap-3" onSubmit={submit} data-testid="bank-account-form">
          {account && <p className="text-muted-foreground text-xs">{t('bank.account.changeHint')}</p>}
          {field('bankCode', { inputMode: 'numeric', maxLength: 8 })}
          {field('bankName', { maxLength: 60 })}
          {field('branchCode', { inputMode: 'numeric', maxLength: 6 })}
          {field('branchName', { maxLength: 60 })}
          <fieldset className="flex flex-col gap-1">
            <legend className="mb-1 text-sm font-medium">{t('bank.account.accountType')}</legend>
            <RadioGroup value={form.accountType} data-testid="bank-accountType"
              onValueChange={(value) => setForm({ ...form, accountType: value as AccountType })}>
              {ACCOUNT_TYPES.map((value) => (
                <div key={value} className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-type-${value}`} value={value} />
                  <Label htmlFor={`${id}-type-${value}`}>{t(`bank.account.types.${value}`)}</Label>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          {field('bankAccountNumber', { inputMode: 'numeric', maxLength: 14 })}
          {field('accountHolder', { maxLength: 96 })}
          {inUse && (
            <Alert variant="destructive" data-testid="bank-in-use">
              <AlertDescription>{t('bank.account.inUse')}</AlertDescription>
            </Alert>
          )}
          {save.isError && (
            <Alert variant="destructive" data-testid="bank-account-problem">
              <AlertDescription>{problemText(save.error)}</AlertDescription>
            </Alert>
          )}
          <div className="flex gap-2">
            <Button type="submit" disabled={save.isPending} data-testid="bank-account-save">
              {t('bank.account.save')}
            </Button>
            {account && (
              <Button type="button" variant="ghost" onClick={() => setEditing(false)}>{t('bank.cancel')}</Button>
            )}
          </div>
        </form>
      )}
    </section>
  )
}

function WithdrawSection({ settings }: { settings: BankSettings }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const queryClient = useQueryClient()
  const wallet = useQuery({ queryKey: [...WALLET_QUERY, 'summary'], queryFn: readWallet })
  const [text, setText] = useState('')
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [done, setDone] = useState(false)
  const amount = /^[0-9]+$/.test(text.trim()) ? Number(text.trim()) : null
  const request = useMutation({
    mutationFn: (value: number) => requestWithdrawal(value, key),
    onSuccess: () => {
      setDone(true)
      setText('')
      setKey(crypto.randomUUID())
      void queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    },
  })
  const most = wallet.data ? transferable(wallet.data) : null
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (amount !== null) request.mutate(amount)
  }
  return (
    <section aria-labelledby={`${id}-title`} className="flex flex-col gap-3 rounded-lg border p-3"
      data-testid="bank-withdraw">
      <h2 id={`${id}-title`} className="font-semibold">{t('bank.withdraw.title')}</h2>
      <p className="text-muted-foreground text-sm">
        {t('bank.withdraw.limits', { min: formatKudos(settings.minimum ?? 0), max: formatKudos(most ?? 0),
          fee: formatKudos(settings.fee) })}
      </p>
      <form className="flex flex-col gap-3" onSubmit={submit}>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-amount`}>{t('bank.withdraw.amount')}</Label>
          <Input id={`${id}-amount`} inputMode="numeric" value={text} data-testid="bank-amount" required
            onChange={(event) => {
              setText(event.target.value)
              setDone(false)
            }} />
        </div>
        {amount !== null && (
          <p className="text-sm" data-testid="bank-net">
            {t('bank.withdraw.net', { fee: formatKudos(settings.fee), net: formatKudos(netOf(amount, settings.fee)) })}
          </p>
        )}
        {request.isError && (
          <Alert variant="destructive" data-testid="bank-withdraw-problem">
            <AlertDescription>{problemText(request.error)}</AlertDescription>
          </Alert>
        )}
        {done && (
          <Alert variant="success" role="status" data-testid="bank-withdraw-done">
            <AlertDescription>{t('bank.withdraw.done')}</AlertDescription>
          </Alert>
        )}
        <Button type="submit" className="self-start" disabled={amount === null || request.isPending}
          data-testid="bank-withdraw-submit">
          {t('bank.withdraw.submit')}
        </Button>
      </form>
    </section>
  )
}
