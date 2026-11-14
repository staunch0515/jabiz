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
  EXPOSURE_FEE_KEY,
  readExposurePrices,
  readExposureSettlements,
  readOwnExposurePrice,
  saveExposurePrice,
  saveOwnExposurePrice,
  unitOf,
  type ExposurePrice,
  type ExposurePriceInput,
  type ExposureRange,
  type ExposureSettlementFilter,
  type ExposureSettlementRow,
} from './exposure-fee-api'
import { EmptyState, LoadFailed, Section } from './components'
import { money, usePaged, when } from './hooks'

/*
 * The exposure fee (F-M8, docs/quizbuks/plans/impl/F-M8.md): each operator's price per exposure of another Sponsor's
 * shared video, the range headquarters allow (the controlled parameters qb.exposure.unit.min and .max, changed on
 * the platform's parameters page with another administrator publishing), and every settlement. The pages only send
 * what is typed; the server checks the value against the range and who may save (an operator's lead).
 */

const REASON_MAX = 500
const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
const right = { className: 'text-right tabular-nums' }

/** One operator's price as a form: the price per exposure and the reason for the change. */
function PriceForm({ price, range, onSave, readOnly }: {
  price: ExposurePrice | null
  range?: ExposureRange
  onSave: (input: ExposurePriceInput) => Promise<unknown>
  readOnly?: boolean
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [unit, setUnit] = useState(price?.unit == null ? '' : String(price.unit))
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const bad = unitOf(unit) === null
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || bad || !reason.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      await onSave({ unit: unitOf(unit)!, reason: reason.trim() })
      notify.success(t('exposureFee.saved'))
      setReason('')
      await queryClient.invalidateQueries({ queryKey: EXPOSURE_FEE_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  const bound = (key: string) => range?.[key] ?? '—'
  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4"
      data-testid="exposure-fee-price-form">
      {price?.updatedAt && (
        <p className="text-muted-foreground text-sm" data-testid="exposure-fee-last-change">
          {t('exposureFee.lastChange', { at: when(price.updatedAt), reason: price.reason ?? '' })}
          {price.updatedBy && <> · <UserName id={price.updatedBy} /></>}
        </p>
      )}
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-unit`}>{t('exposureFee.unit')}</Label>
        <Input id={`${id}-unit`} inputMode="decimal" maxLength={16} className="w-48" value={unit} readOnly={readOnly}
          aria-invalid={(bad && !readOnly) || undefined} aria-describedby={range ? `${id}-unit-hint` : undefined}
          data-testid="exposure-fee-unit" onChange={(event) => setUnit(event.target.value)} />
        {range && (
          <p id={`${id}-unit-hint`} className="text-muted-foreground text-xs">
            {t('exposureFee.rangeHint', { min: bound('qb.exposure.unit.min'), max: bound('qb.exposure.unit.max') })}
          </p>
        )}
      </div>
      {!readOnly && (
        <>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-reason`}>{t('exposureFee.reason')}</Label>
            <Textarea id={`${id}-reason`} value={reason} maxLength={REASON_MAX} rows={2}
              data-testid="exposure-fee-reason" onChange={(event) => setReason(event.target.value)} />
          </div>
          {problem && <p className="text-destructive text-sm" role="alert">{problem}</p>}
          <div>
            <Button type="submit" disabled={busy || bad || !reason.trim()} aria-busy={busy || undefined}
              data-testid="exposure-fee-save">{t('exposureFee.save')}</Button>
          </div>
        </>
      )}
    </form>
  )
}

/** Headquarters: every operator with its price (none: no exposure fee), one picked to change it. */
export function ExposurePricesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const prices = useQuery({ queryKey: [...EXPOSURE_FEE_KEY, 'prices'], queryFn: readExposurePrices })
  const [operator, setOperator] = useState('')
  const rows = prices.data ?? []
  const chosen = rows.find((row) => row.operatorId === operator) ?? null
  const columns = useMemo<ColumnDef<ExposurePrice, unknown>[]>(() => [
    { id: 'operator', header: t('exposureFee.operator'), cell: ({ row }) => row.original.operatorName
      ?? row.original.operatorId },
    { id: 'unit', header: t('exposureFee.unit'), meta: right, cell: ({ row }) => (row.original.unit == null ? '—'
      : String(row.original.unit)) },
    { id: 'updatedAt', header: t('exposureFee.updatedAt'), cell: ({ row }) => (row.original.updatedAt
      ? when(row.original.updatedAt) : t('exposureFee.none')) },
  ], [t])
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('exposureFee.pricesTitle')} description={t('exposureFee.pricesDescription')} />
      {prices.isError
        ? <LoadFailed error={prices.error} onRetry={() => void prices.refetch()} />
        : (
          <>
            <DataTable label={t('exposureFee.pricesTitle')} columns={columns} data={rows} loading={prices.isPending}
              getRowId={(row) => row.operatorId}
              empty={<EmptyState art="money" title={t('exposureFee.noOperators')} />}
              rowProps={(row) => ({ 'data-testid': 'exposure-fee-price-row', 'data-operator': row.operatorId })} />
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-operator`}>{t('exposureFee.operator')}</Label>
              <Select value={operator || undefined} onValueChange={setOperator}>
                <SelectTrigger id={`${id}-operator`} className="w-72" data-testid="exposure-fee-operator">
                  <SelectValue placeholder={t('exposureFee.pickOperator')} />
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
              <Section title={chosen.operatorName ?? chosen.operatorId} testId="exposure-fee-price">
                <PriceForm key={`${chosen.operatorId}-${chosen.updatedAt ?? ''}`}
                  price={chosen.updatedAt ? chosen : null}
                  onSave={(input) => saveExposurePrice(chosen.operatorId, input)} />
              </Section>
            )}
            <p className="text-muted-foreground text-sm" data-testid="exposure-fee-range-note">
              {t('exposureFee.rangeNote')}
            </p>
          </>
        )}
    </div>
  )
}

/** An operator: its own price, which only its lead may save; the headquarters' range beside the value. */
export function OperatorExposurePricePage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const own = useQuery({ queryKey: [...EXPOSURE_FEE_KEY, 'own'], queryFn: readOwnExposurePrice })
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('exposureFee.pricesTitle')} description={t('exposureFee.ownDescription')} />
      {own.isError
        ? <LoadFailed error={own.error} onRetry={() => void own.refetch()} />
        : own.isPending
          ? <p className="text-muted-foreground text-sm">{t('exposureFee.loading')}</p>
          : (
            <Section title={t('exposureFee.pricesTitle')} testId="exposure-fee-price">
              {own.data?.price.updatedAt == null && (
                <p className="text-muted-foreground text-sm" data-testid="exposure-fee-price-none">
                  {t('exposureFee.noPrice')}
                </p>
              )}
              {own.data && !own.data.lead && (
                <p className="text-muted-foreground text-sm" data-testid="exposure-fee-lead-only">
                  {t('exposureFee.leadOnly')}
                </p>
              )}
              <PriceForm key={own.data?.price.updatedAt ?? 'none'}
                price={own.data?.price.updatedAt ? own.data.price : null} range={own.data?.range ?? {}}
                readOnly={!own.data?.lead} onSave={saveOwnExposurePrice} />
            </Section>
          )}
      <Section title={t('exposureFee.settlementsTitle')} testId="exposure-fee-own-settlements">
        <SettlementsList own />
      </Section>
    </div>
  )
}

const PERIOD = /^\d{4}-(0[1-9]|1[0-2])$/

/** Finance: every settlement, by month; exported with the platform's export. */
export function ExposureSettlementsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('exposureFee.settlementsTitle')} description={t('exposureFee.settlementsDescription')} />
      <SettlementsList own={false} />
    </div>
  )
}

/**
 * The settlements, by month: every one (finance) or the caller operator's own (`own`: the publications whose fee is
 * the operator's). An operator does not read users' profiles, so its list shows the Sponsor's id.
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
  const filter: ExposureSettlementFilter = { period: PERIOD.test(periodText.trim()) ? periodText.trim() : null }
  const list = usePaged([...EXPOSURE_FEE_KEY, own ? 'own-settlements' : 'settlements', filter.period],
    (offset, limit) => readExposureSettlements(filter, offset, limit, own).then((page) => ({
      items: page.items, total: page.total ?? undefined })))
  const columns = useMemo<ColumnDef<ExposureSettlementRow, unknown>[]>(() => [
    { id: 'period', header: t('exposureFee.period'), cell: ({ row }) => row.original.period },
    { id: 'publication', header: t('exposureFee.publication'), cell: ({ row }) => row.original.publicationId },
    { id: 'sponsor', header: t('exposureFee.sponsor'), cell: ({ row }) => (own ? (row.original.company ?? row.original.sponsorId)
      : <UserName id={row.original.sponsorId} />) },
    { id: 'counted', header: t('exposureFee.counted'), meta: right, cell: ({ row }) => row.original.counted },
    { id: 'unit', header: t('exposureFee.unit'), meta: right, cell: ({ row }) => String(row.original.unit) },
    { id: 'amount', header: t('exposureFee.amount'), meta: right, cell: ({ row }) => money(String(row.original.amount)) },
    { id: 'tax', header: t('exposureFee.tax'), meta: right, cell: ({ row }) => money(String(row.original.tax)) },
    { id: 'final', header: t('exposureFee.final'), cell: ({ row }) => (row.original.final
      ? t('exposureFee.yes') : '') },
    { id: 'settledAt', header: t('exposureFee.settledAt'), cell: ({ row }) => when(row.original.settledAt) },
  ], [t, own])
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-period`}>{t('exposureFee.period')}</Label>
          <Input id={`${id}-period`} className="w-32" placeholder="2026-11" maxLength={7} value={periodText}
            data-testid="exposure-fee-period" onChange={(event) => setPeriodText(event.target.value)} />
        </div>
      </div>
      {list.query.isError
        ? <LoadFailed error={list.query.error} onRetry={() => void list.query.refetch()} />
        : (
          <DataTable label={t('exposureFee.settlementsTitle')} columns={columns} {...list.table}
            getRowId={(row) => row.settlementId}
            empty={<EmptyState art="money" title={t('exposureFee.noSettlements')} />}
            rowProps={(row) => ({ 'data-testid': 'exposure-fee-settlement-row', 'data-final': String(row.final) })} />
        )}
    </div>
  )
}
