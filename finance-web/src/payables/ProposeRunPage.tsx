import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { Alert, Button, Card, Checkbox, Input, Select, Space, Typography } from 'antd'
import { useId, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import Field from '../receivables/Field'
import { loadDefaultBank, METHODS, proposeRun, type Method } from './api'
import { runPath, RUNS_PATH } from './paths'

/**
 * Proposing a payment run (FIN-AP-010): the day to pay, the method and the bank account, the bills due by a day (or
 * whose early-payment discount runs to the payment day), of all vendors or some. Without a due day the run starts
 * empty, for other payments and prepayments. A run pays bills in one currency: in another than US dollars it is a wire
 * or manual run, at the rate given or the payment day's spot rate (F7 plan decision D4). The server proposes and holds
 * what may not be paid; the run's page shows both.
 */
const FOREIGN_METHODS: Method[] = ['WIRE', 'MANUAL']

export default function ProposeRunPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const navigate = useNavigate()
  const id = useId()
  const bank = useQuery({ queryKey: ['fin', 'defaultBank'], queryFn: loadDefaultBank, staleTime: 60_000 })
  const [paymentDate, setPaymentDate] = useState('')
  const [method, setMethod] = useState<Method>('ACH')
  const [bankCode, setBankCode] = useState('')
  const [dueThrough, setDueThrough] = useState('')
  const [vendors, setVendors] = useState('')
  const [takeDiscounts, setTakeDiscounts] = useState(false)
  const [description, setDescription] = useState('')
  const [currency, setCurrency] = useState('')
  const [exchangeRate, setExchangeRate] = useState('')
  const foreign = currency !== '' && currency !== 'USD'
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // One key per request body: a retry after a lost answer must not make a second run, while a refusal stores nothing
  // and the same key simply runs again.
  const key = useRef<{ key: string, body: string } | null>(null)

  const onSubmit = async (event?: FormEvent) => {
    event?.preventDefault()
    if (busy || !paymentDate) return
    setBusy(true)
    setError(null)
    try {
      const codes = vendors.split(/[\s,;]+/).map((v) => v.trim().toUpperCase()).filter(Boolean)
      const input = { paymentDate, method, bankCode: bankCode.trim() || null,
        dueThrough: dueThrough || null, vendorCodes: codes.length > 0 ? codes : null, takeDiscounts: takeDiscounts && !foreign,
        description: description.trim() || null, currency: currency || null,
        exchangeRate: foreign && exchangeRate.trim() ? exchangeRate.trim() : null }
      const body = JSON.stringify(input)
      if (key.current?.body !== body) key.current = { key: crypto.randomUUID(), body }
      const output = await proposeRun(input, key.current.key)
      navigate(runPath(output.runId), { state: { held: output.held } })
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => void onSubmit(e)} data-testid="propose-page">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space align="center">
          <Link to={RUNS_PATH}>{t('payables.backToRuns')}</Link>
          <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('payables.newRun')}</Typography.Title>
        </Space>
        {error && <Alert type="error" showIcon message={error} data-testid="propose-error" />}
        <Card size="small">
          <Space wrap size="large" align="end">
            <Field id={`${id}-date`} label={t('payables.paymentDate')}>
              <Input id={`${id}-date`} type="date" value={paymentDate} autoFocus data-testid="run-date"
                style={{ width: 170 }} onChange={(e) => setPaymentDate(e.target.value)} />
            </Field>
            <Field id={`${id}-method`} label={t('payables.method')}>
              <Select<Method> id={`${id}-method`} value={method} onChange={setMethod} style={{ width: 200 }}
                data-testid="run-method" options={METHODS.filter((m) => !foreign || FOREIGN_METHODS.includes(m))
                  .map((m) => ({ value: m, label: t(`payables.methods.${m}`) }))} />
            </Field>
            <Field id={`${id}-bank`} label={t('payables.bank')}>
              <Input id={`${id}-bank`} value={bankCode} maxLength={20} style={{ width: 160 }}
                placeholder={bank.data ?? undefined} onChange={(e) => setBankCode(e.target.value.toUpperCase())} />
            </Field>
            <Field id={`${id}-due`} label={t('payables.dueThrough')}>
              <Input id={`${id}-due`} type="date" value={dueThrough} style={{ width: 170 }} data-testid="run-due"
                onChange={(e) => setDueThrough(e.target.value)} />
            </Field>
            <Field id={`${id}-vendors`} label={t('payables.vendors')}>
              <Input id={`${id}-vendors`} value={vendors} style={{ width: 240 }} placeholder={t('payables.allVendors')}
                data-testid="run-vendors" onChange={(e) => setVendors(e.target.value)} />
            </Field>
            <Field id={`${id}-currency`} label={t('payables.currency')}>
              <Input id={`${id}-currency`} value={currency} maxLength={3} style={{ width: 90 }} placeholder="USD"
                data-testid="run-currency" onChange={(e) => {
                  const code = e.target.value.trim().toUpperCase()
                  setCurrency(code)
                  // Foreign bills are wired or paid outside the files: never by ACH or check.
                  if (code !== '' && code !== 'USD' && !FOREIGN_METHODS.includes(method)) setMethod('WIRE')
                }} />
            </Field>
            {foreign && (
              <Field id={`${id}-rate`} label={t('payables.exchangeRate')}>
                <Input id={`${id}-rate`} value={exchangeRate} inputMode="decimal" style={{ width: 140 }}
                  placeholder={t('payables.spotRate')} data-testid="run-rate"
                  onChange={(e) => setExchangeRate(e.target.value)} />
              </Field>
            )}
            <Checkbox checked={takeDiscounts && !foreign} disabled={foreign}
              onChange={(e) => setTakeDiscounts(e.target.checked)}>
              {t('payables.takeDiscounts')}
            </Checkbox>
            <Field id={`${id}-description`} label={t('payables.description')} grow>
              <Input id={`${id}-description`} value={description} maxLength={500}
                onChange={(e) => setDescription(e.target.value)} />
            </Field>
          </Space>
          <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
            {t('payables.proposeHelp')}
          </Typography.Paragraph>
          {foreign && (
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }} data-testid="run-foreign-help">
              {t('payables.foreignRunHelp', { currency })}
            </Typography.Paragraph>
          )}
        </Card>
        <Button type="primary" htmlType="submit" loading={busy} disabled={!paymentDate} data-testid="run-propose">
          {t('payables.propose')}
        </Button>
      </Space>
    </form>
  )
}
