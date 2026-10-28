import { Button, Checkbox, Input, Label, Switch } from '@jabiz/ui'
import { Users } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { CRITERIA, type Condition, type Criterion } from '../qb/audience'
import { QB } from '../qb/messages'
import { SPONSOR } from '../messages'
import type { AudienceCount } from './api'

/**
 * A draft's matching conditions (Q47, the wizard's step "Customer matching"): per criterion, off (everybody) or the
 * options or the age range a taker's profile must have. Within a criterion any option is enough; every criterion
 * switched on must hold. The page only offers values; the server checks them (`QB_AUDIENCE_*`).
 */
export function AudienceConditions({ value, onChange, disabled }: {
  value: Condition[]
  onChange: (conditions: Condition[]) => void
  disabled: boolean
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

  return (
    <div className="flex flex-col gap-4" data-testid="audience-conditions">
      {CRITERIA.map((criterion) => {
        const condition = find(criterion.key)
        const label = tQb(`audience.${criterion.key}`)
        return (
          <fieldset key={criterion.key} className="flex flex-col gap-3 rounded-md border p-3"
            data-testid={`audience-${criterion.key}`}>
            <div className="flex items-center justify-between gap-2">
              <legend className="text-sm font-medium">{label}</legend>
              <div className="flex items-center gap-2">
                <Label htmlFor={`${id}-${criterion.key}-on`} className="text-muted-foreground font-normal text-xs">
                  {condition ? t('publishing.audience.limited') : t('publishing.audience.anyone')}
                </Label>
                <Switch id={`${id}-${criterion.key}-on`} checked={!!condition} disabled={disabled}
                  aria-label={t('publishing.audience.limit', { criterion: label })}
                  data-testid={`audience-${criterion.key}-on`}
                  onCheckedChange={(on) => (on ? turnOn(criterion) : put(criterion.key, null))} />
              </div>
            </div>
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
