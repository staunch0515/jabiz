import { Button, Input, Label, MoneyInput } from '@jabiz/ui'
import { Plus, X } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { kudosName } from '../qb/kudos'
import { nextTier, PUBLISHING_LIMITS, type TierDraft } from './api'

/**
 * The reward tiers of a draft (M-33): from and to scores (both included) and the reward of each, in their order. The
 * whole list is saved with the draft; the server checks that the tiers cover 0 to the full score once each and lists
 * what does not (the wizard's last step). `problemOf` gives a field's problem: what is typed wrong, or the server's.
 */
export function TiersEditor({ tiers, onChange, fullScore, problemOf, disabled }: {
  tiers: TierDraft[]
  onChange: (tiers: TierDraft[]) => void
  fullScore: number | null
  problemOf: (field: string) => string | undefined
  disabled?: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const set = (index: number, part: 'fromScore' | 'toScore' | 'reward', value: string) =>
    onChange(tiers.map((tier, i) => (i === index ? { ...tier, [part]: value } : tier)))
  const hint = fullScore === null
    ? t('publishing.tiers.hintNone', { max: PUBLISHING_LIMITS.tiers })
    : t('publishing.tiers.hint', { fullScore, max: PUBLISHING_LIMITS.tiers })
  return (
    <fieldset className="flex flex-col gap-3" aria-describedby={`${id}-hint`} data-testid="tiers">
      <legend className="text-sm font-medium">{t('publishing.field.tiers')}</legend>
      <p id={`${id}-hint`} className="text-muted-foreground text-sm">{hint}</p>
      {tiers.length === 0 && <p className="text-muted-foreground text-sm">{t('publishing.tiers.none')}</p>}
      <ol className="flex flex-col gap-3">
        {tiers.map((tier, i) => {
          const number = i + 1
          const field = (part: string) => {
            const name = `tiers[${i}].${part}`
            const problem = problemOf(name)
            const fieldId = `${id}-${tier.key}-${part}`
            return { fieldId, problem, errorId: `${fieldId}-error` }
          }
          const from = field('fromScore')
          const to = field('toScore')
          const reward = field('reward')
          const invalid = (f: { problem?: string; errorId: string }) => ({
            'aria-invalid': f.problem ? true : undefined,
            'aria-describedby': f.problem ? f.errorId : undefined,
          })
          return (
            <li key={tier.key} className="flex flex-col gap-2 rounded-md border p-3" data-testid="tier">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-medium">{t('publishing.tiers.tier', { number })}</span>
                <Button type="button" variant="ghost" size="sm" disabled={disabled}
                  aria-label={t('publishing.tiers.remove', { number })} data-testid="tier-remove"
                  onClick={() => onChange(tiers.filter((_, k) => k !== i))}>
                  <X aria-hidden />
                </Button>
              </div>
              <div className="grid gap-3 sm:grid-cols-3">
                <div className="flex flex-col gap-1">
                  <Label htmlFor={from.fieldId}>
                    {t('publishing.tiers.from')}<span className="sr-only">, {t('publishing.tiers.tier', { number })}</span>
                  </Label>
                  <Input id={from.fieldId} inputMode="numeric" value={tier.fromScore} disabled={disabled}
                    autoComplete="off" data-testid="tier-from" {...invalid(from)}
                    onChange={(event) => set(i, 'fromScore', event.target.value)} />
                  {from.problem && <p id={from.errorId} className="text-destructive text-sm">{from.problem}</p>}
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={to.fieldId}>
                    {t('publishing.tiers.to')}<span className="sr-only">, {t('publishing.tiers.tier', { number })}</span>
                  </Label>
                  <Input id={to.fieldId} inputMode="numeric" value={tier.toScore} disabled={disabled}
                    autoComplete="off" data-testid="tier-to" {...invalid(to)}
                    onChange={(event) => set(i, 'toScore', event.target.value)} />
                  {to.problem && <p id={to.errorId} className="text-destructive text-sm">{to.problem}</p>}
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={reward.fieldId}>
                    {t('publishing.tiers.reward')}<span className="sr-only">, {t('publishing.tiers.tier', { number })}</span>
                  </Label>
                  <MoneyInput id={reward.fieldId} value={tier.reward} scale={0} allowNegative={false} unit={kudosName()}
                    disabled={disabled} autoComplete="off" data-testid="tier-reward" {...invalid(reward)}
                    onChange={(value) => set(i, 'reward', value)} />
                  {reward.problem && <p id={reward.errorId} className="text-destructive text-sm">{reward.problem}</p>}
                </div>
              </div>
            </li>
          )
        })}
      </ol>
      <Button type="button" variant="outline" className="w-fit" data-testid="tier-add"
        disabled={disabled || tiers.length >= PUBLISHING_LIMITS.tiers}
        onClick={() => onChange([...tiers, nextTier(tiers, fullScore)])}>
        <Plus aria-hidden />
        {t('publishing.tiers.add')}
      </Button>
    </fieldset>
  )
}
