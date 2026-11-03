import { Button, Checkbox, Input, Label, Switch } from '@jabiz/ui'
import { Users } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { CRITERIA, type Condition, type Criterion } from '../qb/audience'
import { QB } from '../qb/messages'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import type { AudienceCount, FeeQuote, RulePrice } from './api'

/**
 * A draft's matching conditions (Q47, the wizard's step "Customer matching"): per criterion, off (everybody) or the
 * options or the age range a taker's profile must have. Within a criterion any option is enough; every criterion
 * switched on must hold. The page only offers values; the server checks them (`QB_AUDIENCE_*`).
 */
export function AudienceConditions({ value, onChange, disabled, rules, charged }: {
  value: Condition[]
  onChange: (conditions: Condition[]) => void
  disabled: boolean
  /** Q47c: each rule as headquarters manage it, with the daily price for this sponsor (absent: not read yet). */
  rules?: readonly RulePrice[]
  /** Whether conditions are charged for now (the quote's `enabled`): only then are prices shown. */
  charged?: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const find = (key: string) => value.find((condition) => condition.key === key)
  // Kept in the criteria's order, so that the server's paths (criteria[i]) follow the page.
  const put = (key: string, condition: Condition | null) => onChange(CRITERIA.flatMap((criterion) => {
    if (criterion.key !== key) {
      const kept = find(criterion.key)
      return kept ? [kept] : []
    }
    return condition ? [condition] : []
  }))
  const turnOn = (criterion: Criterion) => put(criterion.key, criterion.kind === 'RANGE'
    ? { key: criterion.key, min: 20, max: 39 }
    : { key: criterion.key, options: [] })

  const ruleOf = (key: string) => rules?.find((rule) => rule.ruleKey === key)
  // Headquarters' order where they gave one; the stored order (and the server's paths) stays the code's.
  const shown = rules
    ? [...CRITERIA].sort((a, b) => (ruleOf(a.key)?.sortOrder ?? 0) - (ruleOf(b.key)?.sortOrder ?? 0))
    : CRITERIA

  return (
    <div className="flex flex-col gap-4" data-testid="audience-conditions">
      {shown.map((criterion) => {
        const condition = find(criterion.key)
        const label = tQb(`audience.${criterion.key}`)
        const rule = ruleOf(criterion.key)
        const closed = rule?.enabled === false
        return (
          <fieldset key={criterion.key} className="flex flex-col gap-3 rounded-md border p-3"
            data-testid={`audience-${criterion.key}`}>
            <div className="flex items-center justify-between gap-2">
              <div className="flex flex-wrap items-baseline gap-x-2">
                <legend className="text-sm font-medium">{label}</legend>
                {closed
                  ? <span className="text-muted-foreground text-xs" data-testid={`audience-${criterion.key}-closed`}>
                    {t('publishing.audience.closed')}
                  </span>
                  : charged && rule?.billable && (
                    <span className="text-muted-foreground text-xs" data-testid={`audience-${criterion.key}-price`}>
                      {t('publishing.audience.perDay', { price: formatKudos(rule.dailyPrice) })}
                    </span>
                  )}
              </div>
              <div className="flex items-center gap-2">
                <Label htmlFor={`${id}-${criterion.key}-on`} className="text-muted-foreground font-normal text-xs">
                  {condition ? t('publishing.audience.limited') : t('publishing.audience.anyone')}
                </Label>
                <Switch id={`${id}-${criterion.key}-on`} checked={!!condition} disabled={disabled || (closed && !condition)}
                  aria-label={t('publishing.audience.limit', { criterion: label })}
                  data-testid={`audience-${criterion.key}-on`}
                  onCheckedChange={(on) => (on ? turnOn(criterion) : put(criterion.key, null))} />
              </div>
            </div>
            {condition && closed && (
              <p className="text-destructive text-sm" role="alert">{t('publishing.audience.closedOn')}</p>
            )}
            {condition && criterion.kind === 'SET' && (
              <div className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-4">
                {criterion.options.map((option) => {
                  const chosen = condition.options ?? []
                  return (
                    <div key={option} className="flex items-center gap-2">
                      <Checkbox id={`${id}-${criterion.key}-${option}`} checked={chosen.includes(option)}
                        disabled={disabled} data-testid={`audience-${criterion.key}-${option}`}
                        onCheckedChange={(checked) => put(criterion.key, { key: criterion.key, options: checked === true
                          ? criterion.options.filter((o) => o === option || chosen.includes(o))
                          : chosen.filter((o) => o !== option) })} />
                      <Label htmlFor={`${id}-${criterion.key}-${option}`} className="font-normal">
                        {tQb(`audience.${criterion.key}Options.${option}`)}
                      </Label>
                    </div>
                  )
                })}
              </div>
            )}
            {condition && criterion.kind === 'RANGE' && (
              <div className="flex flex-wrap items-end gap-3">
                {(['min', 'max'] as const).map((bound) => (
                  <div key={bound} className="flex flex-col gap-1">
                    <Label htmlFor={`${id}-${criterion.key}-${bound}`} className="font-normal">
                      {t(`publishing.audience.${bound}`)}
                    </Label>
                    <Input id={`${id}-${criterion.key}-${bound}`} inputMode="numeric" maxLength={3}
                      className="w-24" disabled={disabled} data-testid={`audience-${criterion.key}-${bound}`}
                      value={condition[bound] == null ? '' : String(condition[bound])}
                      onChange={(event) => {
                        const digits = event.target.value.replace(/\D/g, '')
                        put(criterion.key, { ...condition, [bound]: digits === '' ? null : Number(digits) })
                      }} />
                  </div>
                ))}
                <p className="text-muted-foreground text-xs">
                  {t('publishing.audience.rangeHint', { min: criterion.min, max: criterion.max })}
                </p>
              </div>
            )}
          </fieldset>
        )
      })}
    </div>
  )
}

/** The count button and its answer: "fewer than 20", or about N (rounded down to tens). */
export function AudienceCountPanel({ count, pending, disabled, onCount }: {
  count: AudienceCount | null
  pending: boolean
  disabled: boolean
  onCount: () => void
}) {
  const { t } = useTranslation(SPONSOR)
  return (
    <div className="flex flex-col gap-2 rounded-md border p-3" data-testid="audience-count">
      <p className="text-muted-foreground text-sm">{t('publishing.audience.countHint')}</p>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="outline" disabled={disabled || pending} aria-busy={pending || undefined}
          data-testid="audience-count-button" onClick={onCount}>
          <Users aria-hidden />
          {t('publishing.audience.count')}
        </Button>
        <p role="status" className="text-sm font-medium empty:hidden" data-testid="audience-count-result">
          {count == null ? '' : count.count == null
            ? t('publishing.audience.fewer', { n: count.fewerThan ?? 20 })
            : t('publishing.audience.about', { n: count.count.toLocaleString() })}
        </p>
      </div>
    </div>
  )
}

/**
 * Q47c: what the saved conditions cost over the saved run, as the server quotes it; nothing while conditions are not
 * charged for. The page shows the quote only: it never works the fee out itself.
 */
export function AudienceFeePanel({ quote, pending }: { quote: FeeQuote | null; pending: boolean }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  if (!pending && !quote?.enabled) return null
  return (
    <div className="flex flex-col gap-2 rounded-md border p-3" data-testid="audience-fee">
      <h4 className="text-sm font-medium">{t('publishing.audience.fee.title')}</h4>
      <p className="text-muted-foreground text-sm">{t('publishing.audience.fee.hint')}</p>
      {pending || !quote
        ? <p className="text-muted-foreground text-sm">{t('publishing.audience.fee.loading')}</p>
        : quote.owner === null
          ? <p className="text-sm" data-testid="audience-fee-free">{t('publishing.audience.fee.free')}</p>
          : quote.items.length === 0
            ? <p className="text-sm" data-testid="audience-fee-none">{t('publishing.audience.fee.none')}</p>
            : (
              <>
                <ul className="flex flex-col gap-1 text-sm">
                  {quote.items.map((item) => (
                    <li key={item.ruleKey} data-testid="audience-fee-line">
                      {t('publishing.audience.fee.line', { rule: tQb(`audience.${item.ruleKey}`),
                        price: formatKudos(item.dailyPrice), days: quote.days })}
                    </li>
                  ))}
                </ul>
                {quote.minimumFee > quote.subtotal && (
                  <p className="text-muted-foreground text-sm" data-testid="audience-fee-minimum">
                    {t('publishing.audience.fee.minimum', { fee: formatKudos(quote.minimumFee) })}
                  </p>
                )}
                <p className="text-sm font-medium" role="status" data-testid="audience-fee-total">
                  {t('publishing.audience.fee.total', { fee: formatKudos(quote.fee) })}
                </p>
              </>
            )}
    </div>
  )
}
