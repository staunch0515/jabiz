import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ApiError,
  Button,
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
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Textarea,
  UserName,
} from '@jabiz/admin'
import {
  AUDIENCE_PRICING_KEY,
  readOwnPriceList,
  readPriceLists,
  readRules,
  saveOwnPriceList,
  savePriceList,
  saveRules,
  type AudienceRule,
  type PriceList,
  type PricesInput,
} from './audience-pricing-api'
import { LoadFailed, Section } from './components'
import { when } from './hooks'
import { AUDIENCE_RULE_KEYS } from './ids'

/*
 * The audience rules as a product line and their prices (Q47c, docs/quizbuks/plans/Q47c-audience-billing.md). The
 * pages only send what is typed; the server checks the keys, the amounts and who may save (an operator's lead).
 */

const REASON_MAX = 500
const PRICE_MAX = 9_999_999
const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/** A whole amount as typed, or null when it is not one the server takes. */
function amountOf(text: string): number | null {
  const trimmed = text.trim().replace(/,/g, '')
  if (!/^\d{1,7}$/.test(trimmed)) return null
  const value = Number(trimmed)
  return value <= PRICE_MAX ? value : null
}

function useRuleName() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (key: string) => t(`audiencePricing.names.${key}`, { defaultValue: key })
}

/** Headquarters: which rules sponsors may use, which are charged for, and their order (`QB_AUDIENCE_RULES_SET`). */
export function AudienceRulesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const rules = useQuery({ queryKey: [...AUDIENCE_PRICING_KEY, 'rules'], queryFn: readRules })
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('audiencePricing.rulesTitle')} description={t('audiencePricing.rulesDescription')} />
      {rules.isError
        ? <LoadFailed error={rules.error} onRetry={() => void rules.refetch()} />
        : rules.data
          ? <RulesForm key={rules.dataUpdatedAt} rules={rules.data} />
          : <p className="text-muted-foreground text-sm">{t('audiencePricing.loading')}</p>}
    </div>
  )
}

function RulesForm({ rules }: { rules: AudienceRule[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const name = useRuleName()
  const id = useId()
  const queryClient = useQueryClient()
  const [rows, setRows] = useState(rules.map((rule) => ({ ...rule, order: String(rule.sortOrder) })))
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const put = (key: string, change: Partial<(typeof rows)[number]>) =>
    setRows((current) => current.map((row) => (row.ruleKey === key ? { ...row, ...change } : row)))
  const badOrder = rows.some((row) => !/^\d{1,6}$/.test(row.order.trim()))
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || badOrder) return
    setBusy(true)
    setProblem(null)
    try {
      await saveRules(rows.map((row) => ({ ...row, sortOrder: Number(row.order.trim()) })))
      notify.success(t('audiencePricing.rulesSaved'))
      await queryClient.invalidateQueries({ queryKey: AUDIENCE_PRICING_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4"
      data-testid="audience-rules-form">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t('audiencePricing.rule')}</TableHead>
            <TableHead>{t('audiencePricing.enabled')}</TableHead>
            <TableHead>{t('audiencePricing.billable')}</TableHead>
            <TableHead>{t('audiencePricing.sortOrder')}</TableHead>
            <TableHead>{t('audiencePricing.updated')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.ruleKey} data-testid="audience-rule-row" data-rule={row.ruleKey}>
              <TableCell className="font-medium">{name(row.ruleKey)}</TableCell>
              <TableCell>
                <Switch checked={row.enabled} aria-label={`${name(row.ruleKey)}: ${t('audiencePricing.enabled')}`}
                  data-testid="audience-rule-enabled" onCheckedChange={(on) => put(row.ruleKey, { enabled: on })} />
              </TableCell>
              <TableCell>
                <Switch checked={row.billable} aria-label={`${name(row.ruleKey)}: ${t('audiencePricing.billable')}`}
                  data-testid="audience-rule-billable" onCheckedChange={(on) => put(row.ruleKey, { billable: on })} />
              </TableCell>
              <TableCell>
                <Input id={`${id}-${row.ruleKey}-order`} inputMode="numeric" maxLength={6} className="w-20"
                  aria-label={`${name(row.ruleKey)}: ${t('audiencePricing.sortOrder')}`}
                  aria-invalid={!/^\d{1,6}$/.test(row.order.trim()) || undefined}
                  value={row.order} data-testid="audience-rule-order"
                  onChange={(event) => put(row.ruleKey, { order: event.target.value })} />
              </TableCell>
              <TableCell className="text-muted-foreground text-sm">
                {row.updatedAt
                  ? <>{when(row.updatedAt)} · {row.updatedBy && <UserName id={row.updatedBy} />}</>
                  : t('audiencePricing.neverChanged')}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {problem && <p className="text-destructive text-sm" role="alert">{problem}</p>}
      <div>
        <Button type="submit" disabled={busy || badOrder} aria-busy={busy || undefined}
          data-testid="audience-rules-save">{t('audiencePricing.saveRules')}</Button>
      </div>
    </form>
  )
}

/** One owner's list as a form: the least fee, a daily price per rule, and the reason for the change. */
function PriceListForm({ list, onSave, testId }: {
  list: PriceList | null
  onSave: (input: PricesInput) => Promise<unknown>
  testId: string
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const name = useRuleName()
  const id = useId()
  const queryClient = useQueryClient()
  const [minimum, setMinimum] = useState(String(list?.minimumFee ?? 0))
  const [prices, setPrices] = useState<Record<string, string>>(Object.fromEntries(AUDIENCE_RULE_KEYS.map((key) =>
    [key, String(list?.dailyPrices[key] ?? 0)])))
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const bad = amountOf(minimum) === null || AUDIENCE_RULE_KEYS.some((key) => amountOf(prices[key] ?? '') === null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || bad || !reason.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      await onSave({ minimumFee: amountOf(minimum)!, reason: reason.trim(),
        prices: AUDIENCE_RULE_KEYS.map((ruleKey) => ({ ruleKey, dailyPrice: amountOf(prices[ruleKey] ?? '')! })) })
      notify.success(t('audiencePricing.pricesSaved'))
      setReason('')
      await queryClient.invalidateQueries({ queryKey: AUDIENCE_PRICING_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4" data-testid={testId}>
      {list?.updatedAt && (
        <p className="text-muted-foreground text-sm" data-testid="price-list-last-change">
          {t('audiencePricing.lastChange', { at: when(list.updatedAt), reason: list.reason ?? '' })}
          {list.updatedBy && <> · <UserName id={list.updatedBy} /></>}
        </p>
      )}
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-minimum`}>{t('audiencePricing.minimumFee')}</Label>
        <Input id={`${id}-minimum`} inputMode="numeric" maxLength={9} className="w-40" value={minimum}
          aria-invalid={amountOf(minimum) === null || undefined} aria-describedby={`${id}-hint`}
          data-testid="price-list-minimum" onChange={(event) => setMinimum(event.target.value)} />
      </div>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {AUDIENCE_RULE_KEYS.map((key) => (
          <div key={key} className="flex flex-col gap-2">
            <Label htmlFor={`${id}-${key}`}>{t('audiencePricing.dailyPrice')}: {name(key)}</Label>
            <Input id={`${id}-${key}`} inputMode="numeric" maxLength={9} value={prices[key] ?? ''}
              aria-invalid={amountOf(prices[key] ?? '') === null || undefined} aria-describedby={`${id}-hint`}
              data-testid="price-list-price" data-rule={key}
              onChange={(event) => setPrices((current) => ({ ...current, [key]: event.target.value }))} />
          </div>
        ))}
      </div>
      <p id={`${id}-hint`} className="text-muted-foreground text-xs">{t('audiencePricing.priceInvalid')}</p>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-reason`}>{t('audiencePricing.reason')}</Label>
        <Textarea id={`${id}-reason`} value={reason} maxLength={REASON_MAX} rows={2}
          aria-describedby={`${id}-reason-hint`} data-testid="price-list-reason"
          onChange={(event) => setReason(event.target.value)} />
        <p id={`${id}-reason-hint`} className="text-muted-foreground text-xs">{t('audiencePricing.reasonHint')}</p>
      </div>
      {problem && <p className="text-destructive text-sm" role="alert">{problem}</p>}
      <div>
        <Button type="submit" disabled={busy || bad || !reason.trim()} aria-busy={busy || undefined}
          data-testid="price-list-save">{t('audiencePricing.savePrices')}</Button>
      </div>
    </form>
  )
}

/** Headquarters: the operators' lists (it has none of its own), an operator picked from the lists or by its ID. */
export function AudiencePricesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const lists = useQuery({ queryKey: [...AUDIENCE_PRICING_KEY, 'lists'], queryFn: readPriceLists })
  const [owner, setOwner] = useState<string>('')
  const [typed, setTyped] = useState('')
  const known = lists.data ?? []
  const ownerOf = (list: PriceList) => list.operatorName ?? list.owner
  // The server takes an operator as the owner of every list; none is chosen until the page is told which.
  const chosen = typed.trim() || owner
  const list = known.find((l) => l.owner === chosen) ?? null
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('audiencePricing.pricesTitle')} description={t('audiencePricing.pricesDescription')} />
      {lists.isError
        ? <LoadFailed error={lists.error} onRetry={() => void lists.refetch()} />
        : !lists.data
          ? <p className="text-muted-foreground text-sm">{t('audiencePricing.loading')}</p>
          : (
            <>
              <div className="flex flex-wrap items-end gap-4">
                <div className="flex flex-col gap-2">
                  <Label htmlFor={`${id}-owner`}>{t('audiencePricing.owner')}</Label>
                  <Select value={owner || undefined} onValueChange={(value) => { setOwner(value); setTyped('') }}>
                    <SelectTrigger id={`${id}-owner`} className="w-72" data-testid="price-list-owner">
                      <SelectValue placeholder={t('audiencePricing.pickOwner')} />
                    </SelectTrigger>
                    <SelectContent>
                      {known.map((l) => (
                        <SelectItem key={l.owner} value={l.owner}>{ownerOf(l)}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex flex-col gap-2">
                  <Label htmlFor={`${id}-operator`}>{t('audiencePricing.operatorId')}</Label>
                  <Input id={`${id}-operator`} value={typed} maxLength={64} className="w-80"
                    aria-describedby={`${id}-operator-hint`} data-testid="price-list-operator-id"
                    onChange={(event) => setTyped(event.target.value)} />
                  <p id={`${id}-operator-hint`} className="text-muted-foreground text-xs">
                    {t('audiencePricing.operatorIdHint')}
                  </p>
                </div>
              </div>
              {chosen
                ? (
                  <Section title={list ? ownerOf(list) : chosen} testId="price-list">
                    {!list && (
                      <p className="text-muted-foreground text-sm" data-testid="price-list-none">
                        {t('audiencePricing.noList')}
                      </p>
                    )}
                    <PriceListForm key={`${chosen}-${list?.updatedAt ?? ''}`} list={list} testId="price-list-form"
                      onSave={(input) => savePriceList(chosen, input)} />
                  </Section>
                )
                : <p className="text-muted-foreground text-sm" data-testid="price-list-pick">{t('audiencePricing.pickOwnerHint')}</p>}
            </>
          )}
    </div>
  )
}

/** An operator: its own list, which its lead alone may save (the server refuses the other members). */
export function OperatorAudiencePricesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const list = useQuery({ queryKey: [...AUDIENCE_PRICING_KEY, 'own'], queryFn: readOwnPriceList })
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('audiencePricing.pricesTitle')} description={t('audiencePricing.ownPricesDescription')} />
      {list.isError
        ? <LoadFailed error={list.error} onRetry={() => void list.refetch()} />
        : list.isPending
          ? <p className="text-muted-foreground text-sm">{t('audiencePricing.loading')}</p>
          : (
            <Section title={t('audiencePricing.pricesTitle')} testId="price-list">
              {!list.data && (
                <p className="text-muted-foreground text-sm" data-testid="price-list-none">
                  {t('audiencePricing.noList')}
                </p>
              )}
              <PriceListForm key={list.data?.updatedAt ?? 'none'} list={list.data} testId="price-list-form"
                onSave={saveOwnPriceList} />
            </Section>
          )}
    </div>
  )
}
