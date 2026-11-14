import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import {
  ApiError,
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import {
  DISTRIBUTION_KEY,
  readDistributionPrices,
  readDistributionSettlements,
  readOwnDistributionPrice,
  saveDistributionPrice,
  saveOwnDistributionPrice,
  type DistributionPrice,
  type DistributionPriceInput,
  type DistributionRange,
  type DistributionSettlementRow,
  type SettlementFilter,
  decimalOf,
  wholeOf,
} from './distribution-api'
import { EmptyState, LoadFailed, Section } from './components'
import { money, usePaged, when } from './hooks'

/*
 * The distribution fee (F-M6, docs/quizbuks/plans/impl/F-M.md §9): each operator's price, the range headquarters
 * allow (the controlled parameters qb.distribution.*, changed on the platform's parameters page with another
 * administrator publishing), and every settlement. The pages only send what is typed; the server checks the values
 * against the range and who may save (an operator's lead).
 */

const REASON_MAX = 500
const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
const right = { className: 'text-right tabular-nums' }

const show = (value: number | null) => (value == null ? '—' : String(value))

/** One operator's price as a form: the four values and the reason for the change. */
function PriceForm({ price, range, onSave, readOnly }: {
  price: DistributionPrice | null
  range?: DistributionRange
  onSave: (input: DistributionPriceInput) => Promise<unknown>
  readOnly?: boolean
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [rate, setRate] = useState(price?.rate == null ? '' : String(price.rate))
  const [floor, setFloor] = useState(price?.unitFloor == null ? '' : String(price.unitFloor))
  const [ceiling, setCeiling] = useState(price?.unitCeiling == null ? '' : String(price.unitCeiling))
  const [deposit, setDeposit] = useState(price?.minimumDeposit == null ? '' : String(price.minimumDeposit))
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const bad = decimalOf(rate) === null || decimalOf(floor) === null || decimalOf(ceiling) === null
    || wholeOf(deposit) === null
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || bad || !reason.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      await onSave({ rate: decimalOf(rate)!, unitFloor: decimalOf(floor)!, unitCeiling: decimalOf(ceiling)!,
        minimumDeposit: wholeOf(deposit)!, reason: reason.trim() })
      notify.success(t('distribution.saved'))
      setReason('')
      await queryClient.invalidateQueries({ queryKey: DISTRIBUTION_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  const field = (key: string, label: string, value: string, set: (v: string) => void, valid: boolean,
    hint?: string) => (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`${id}-${key}`}>{label}</Label>
      <Input id={`${id}-${key}`} inputMode="decimal" maxLength={16} className="w-48" value={value} readOnly={readOnly}
        aria-invalid={(!valid && !readOnly) || undefined} aria-describedby={hint ? `${id}-${key}-hint` : undefined}
        data-testid={`distribution-${key}`} onChange={(event) => set(event.target.value)} />
      {hint && <p id={`${id}-${key}-hint`} className="text-muted-foreground text-xs">{hint}</p>}
    </div>
  )
  const bound = (key: string) => range?.[key] ?? '—'
  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4"
      data-testid="distribution-price-form">
      {price?.updatedAt && (
        <p className="text-muted-foreground text-sm" data-testid="distribution-last-change">
          {t('distribution.lastChange', { at: when(price.updatedAt), reason: price.reason ?? '' })}
          {price.updatedBy && <> · <UserName id={price.updatedBy} /></>}
        </p>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        {field('rate', t('distribution.rate'), rate, setRate, decimalOf(rate) !== null, range
          ? t('distribution.rangeHint', { min: bound('qb.distribution.rate.min'), max: bound('qb.distribution.rate.max') })
          : t('distribution.rateHint'))}
        {field('floor', t('distribution.unitFloor'), floor, setFloor, decimalOf(floor) !== null, range
          ? t('distribution.rangeHint', { min: bound('qb.distribution.unit-floor.min'),
            max: bound('qb.distribution.unit-floor.max') })
          : undefined)}
        {field('ceiling', t('distribution.unitCeiling'), ceiling, setCeiling, decimalOf(ceiling) !== null, range
          ? t('distribution.maxHint', { max: bound('qb.distribution.unit-ceiling.max') }) : undefined)}
        {field('deposit', t('distribution.minimumDeposit'), deposit, setDeposit, wholeOf(deposit) !== null, range
          ? t('distribution.maxHint', { max: bound('qb.distribution.minimum-deposit.max') }) : undefined)}
      </div>
      {!readOnly && (
        <>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-reason`}>{t('distribution.reason')}</Label>
            <Textarea id={`${id}-reason`} value={reason} maxLength={REASON_MAX} rows={2}
              data-testid="distribution-reason" onChange={(event) => setReason(event.target.value)} />
          </div>
          {problem && <p className="text-destructive text-sm" role="alert">{problem}</p>}
          <div>
            <Button type="submit" disabled={busy || bad || !reason.trim()} aria-busy={busy || undefined}
              data-testid="distribution-save">{t('distribution.save')}</Button>
          </div>
        </>
      )}
    </form>
  )
}

/** Headquarters: every operator with its price (none: no distribution fee), one picked to change it. */
export function DistributionPricesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const prices = useQuery({ queryKey: [...DISTRIBUTION_KEY, 'prices'], queryFn: readDistributionPrices })
  const [operator, setOperator] = useState('')
  const rows = prices.data ?? []
  const chosen = rows.find((row) => row.operatorId === operator) ?? null
  const columns = useMemo<ColumnDef<DistributionPrice, unknown>[]>(() => [
    { id: 'operator', header: t('distribution.operator'), cell: ({ row }) => row.original.operatorName
      ?? row.original.operatorId },
    { id: 'rate', header: t('distribution.rate'), meta: right, cell: ({ row }) => show(row.original.rate) },
    { id: 'floor', header: t('distribution.unitFloor'), meta: right, cell: ({ row }) => show(row.original.unitFloor) },
    { id: 'ceiling', header: t('distribution.unitCeiling'), meta: right,
      cell: ({ row }) => show(row.original.unitCeiling) },
    { id: 'deposit', header: t('distribution.minimumDeposit'), meta: right,
      cell: ({ row }) => (row.original.minimumDeposit == null ? '—' : money(String(row.original.minimumDeposit))) },
    { id: 'updatedAt', header: t('distribution.updatedAt'), cell: ({ row }) => (row.original.updatedAt
      ? when(row.original.updatedAt) : t('distribution.none')) },
  ], [t])
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('distribution.pricesTitle')} description={t('distribution.pricesDescription')} />
      {prices.isError
        ? <LoadFailed error={prices.error} onRetry={() => void prices.refetch()} />
        : (
          <>
            <DataTable label={t('distribution.pricesTitle')} columns={columns} data={rows} loading={prices.isPending}
              getRowId={(row) => row.operatorId}
              empty={<EmptyState art="money" title={t('distribution.noOperators')} />}
              rowProps={(row) => ({ 'data-testid': 'distribution-price-row', 'data-operator': row.operatorId })} />
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-operator`}>{t('distribution.operator')}</Label>
              <Select value={operator || undefined} onValueChange={setOperator}>
                <SelectTrigger id={`${id}-operator`} className="w-72" data-testid="distribution-operator">
                  <SelectValue placeholder={t('distribution.pickOperator')} />
                </SelectTrigger>
                <SelectContent>
                  {rows.map((row) => (
                    <SelectItem key={row.operatorId} value={row.operatorId}>
                      {row.operatorName ?? row.operatorId}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            {chosen && (
              <Section title={chosen.operatorName ?? chosen.operatorId} testId="distribution-price">
                <PriceForm key={`${chosen.operatorId}-${chosen.updatedAt ?? ''}`}
                  price={chosen.updatedAt ? chosen : null}
                  onSave={(input) => saveDistributionPrice(chosen.operatorId, input)} />
              </Section>
            )}
            <p className="text-muted-foreground text-sm" data-testid="distribution-range-note">
              {t('distribution.rangeNote')}
            </p>
          </>
        )}
    </div>
  )
}

/** An operator: its own price, which only its lead may save; the headquarters' range beside each value. */
export function OperatorDistributionPricePage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const own = useQuery({ queryKey: [...DISTRIBUTION_KEY, 'own'], queryFn: readOwnDistributionPrice })
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('distribution.pricesTitle')} description={t('distribution.ownDescription')} />
      {own.isError
        ? <LoadFailed error={own.error} onRetry={() => void own.refetch()} />
        : own.isPending
          ? <p className="text-muted-foreground text-sm">{t('distribution.loading')}</p>
          : (
            <Section title={t('distribution.pricesTitle')} testId="distribution-price">
              {own.data?.price.updatedAt == null && (
                <p className="text-muted-foreground text-sm" data-testid="distribution-price-none">
                  {t('distribution.noPrice')}
                </p>
              )}
              {own.data && !own.data.lead && (
                <p className="text-muted-foreground text-sm" data-testid="distribution-lead-only">
                  {t('distribution.leadOnly')}
                </p>
              )}
              <PriceForm key={own.data?.price.updatedAt ?? 'none'}
                price={own.data?.price.updatedAt ? own.data.price : null} range={own.data?.range ?? {}}
                readOnly={!own.data?.lead} onSave={saveOwnDistributionPrice} />
            </Section>
          )}
      <Section title={t('distribution.settlementsTitle')} testId="distribution-own-settlements">
        <SettlementsList own />
      </Section>
    </div>
  )
}

const PERIOD = /^\d{4}-(0[1-9]|1[0-2])$/

/** Finance: every settlement, by month and whether it was charged the floor; exported with the platform's export. */
export function DistributionSettlementsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('distribution.settlementsTitle')} description={t('distribution.settlementsDescription')} />
      <SettlementsList own={false} />
    </div>
  )
}

/**
 * The settlements, by month and whether they were charged the floor: every one (finance) or the caller operator's
 * own (`own`, review L5: the publications whose fee is the operator's, the floor marked). An operator does not read
 * users' profiles, so its list shows the Sponsor's id.
 */
function SettlementsList({ own }: { own: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  // F-M14b: the reconciliation page links a month here (`?period=2026-11`).
  const [search] = useSearchParams()
  const linked = search.get('period') ?? ''
  const [periodText, setPeriodText] = useState(linked)
  // Another link to this page (or back and forward) brings another month: the field follows it.
  const [seen, setSeen] = useState(linked)
  if (linked !== seen) {
    setSeen(linked)
    setPeriodText(linked)
  }
  const [floorOnly, setFloorOnly] = useState(false)
  const filter: SettlementFilter = { period: PERIOD.test(periodText.trim()) ? periodText.trim() : null,
    atFloor: floorOnly ? true : null }
  const list = usePaged([...DISTRIBUTION_KEY, own ? 'own-settlements' : 'settlements', filter.period, filter.atFloor],
    (offset, limit) => readDistributionSettlements(filter, offset, limit, own).then((page) => ({
      items: page.items, total: page.total ?? undefined })))
  const columns = useMemo<ColumnDef<DistributionSettlementRow, unknown>[]>(() => [
    { id: 'period', header: t('distribution.period'), cell: ({ row }) => row.original.period },
    { id: 'publication', header: t('distribution.publication'), cell: ({ row }) => row.original.publicationId },
    { id: 'sponsor', header: t('distribution.sponsor'), cell: ({ row }) => (own ? row.original.sponsorId
      : <UserName id={row.original.sponsorId} />) },
    // Headquarters' list: the operator by its name (the template reads it); an operator's own list leaves it out.
    ...(own ? [] : [{ id: 'operator', header: t('distribution.operator'), cell: ({ row }) =>
      row.original.operatorName ?? row.original.operatorId ?? '' } satisfies ColumnDef<DistributionSettlementRow, unknown>]),
    { id: 'starts', header: t('distribution.starts'), meta: right, cell: ({ row }) => row.original.starts },
    { id: 'unit', header: t('distribution.unit'), meta: right, cell: ({ row }) => (row.original.atFloor
      ? t('distribution.atFloor', { unit: row.original.unit }) : String(row.original.unit)) },
    { id: 'amount', header: t('distribution.amount'), meta: right, cell: ({ row }) => money(String(row.original.amount)) },
    { id: 'tax', header: t('distribution.tax'), meta: right, cell: ({ row }) => money(String(row.original.tax)) },
    { id: 'final', header: t('distribution.final'), cell: ({ row }) => (row.original.final
      ? t('distribution.yes') : '') },
    { id: 'settledAt', header: t('distribution.settledAt'), cell: ({ row }) => when(row.original.settledAt) },
  ], [t, own])
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-period`}>{t('distribution.period')}</Label>
          <Input id={`${id}-period`} className="w-32" placeholder="2026-11" maxLength={7} value={periodText}
            data-testid="distribution-period" onChange={(event) => setPeriodText(event.target.value)} />
        </div>
        <Button variant={floorOnly ? 'default' : 'outline'} size="sm" aria-pressed={floorOnly}
          data-testid="distribution-floor-only" onClick={() => setFloorOnly((value) => !value)}>
          {t('distribution.floorOnly')}
        </Button>
      </div>
      {list.query.isError
        ? <LoadFailed error={list.query.error} onRetry={() => void list.query.refetch()} />
        : (
          <DataTable label={t('distribution.settlementsTitle')} columns={columns} {...list.table}
            getRowId={(row) => row.settlementId}
            empty={<EmptyState art="money" title={t('distribution.noSettlements')} />}
            rowProps={(row) => ({ 'data-testid': 'distribution-settlement-row', 'data-floor': String(row.atFloor) })} />
        )}
    </div>
  )
}
