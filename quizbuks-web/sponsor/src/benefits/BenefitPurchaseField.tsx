import { Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { BENEFITS_QUERY, readPurchases } from './api'
import './messages'

const OWN = 'OWN'

/**
 * Q89b-1 (D-Q89-6): which confirmed purchase of another sponsor's goods or service a prize collected at a provider's
 * place is, or none (the sponsor's own prize). The server checks the rest when the draft is submitted: one tier worth
 * the benefit, winners no more than what is left.
 */
export function BenefitPurchaseField({ id, method, value, disabled, problem, onChange }: {
  id: string
  /** Q89b-2: a digital prize draws on a digital product, a prize collected on goods or a service. */
  method: 'OFFLINE_REDEEM' | 'DIGITAL_PRIZE'
  value: string
  disabled?: boolean
  problem?: string
  onChange: (value: string) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const purchases = useQuery({ queryKey: [...BENEFITS_QUERY, 'purchases', 'SPONSOR'],
    queryFn: () => readPurchases('SPONSOR'), staleTime: 30_000 })
  const usable = (purchases.data ?? []).filter((p) => p.status === 'CONFIRMED'
    && (p.kind === 'DIGITAL') === (method === 'DIGITAL_PRIZE'))
  const chosen = usable.find((p) => p.purchaseId === value)
  // A draft keeps a purchase that is no longer usable: it shows, and the submission says why.
  const shown = value && !chosen ? [{ purchaseId: value, title: value, quantity: 0, unitValue: 0 }] : []
  if (usable.length === 0 && !value) return null
  return (
    <div className="flex flex-col gap-2" data-testid="publication-benefit">
      <Label htmlFor={id}>{t('benefits.publishing.label')}</Label>
      <Select value={value || OWN} disabled={disabled} onValueChange={(next) => onChange(next === OWN ? '' : next)}>
        <SelectTrigger id={id} aria-describedby={`${id}-hint`} aria-invalid={problem ? true : undefined}
          data-testid="publication-benefit-select">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={OWN}>{t('benefits.publishing.own')}</SelectItem>
          {[...usable, ...shown].map((p) => (
            <SelectItem key={p.purchaseId} value={p.purchaseId}>
              {p.quantity ? t('benefits.publishing.option', { title: p.title, quantity: p.quantity.toLocaleString(),
                value: formatKudos(p.unitValue) }) : p.title}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <p id={`${id}-hint`} className="text-muted-foreground text-sm">
        {chosen ? t(method === 'DIGITAL_PRIZE' ? 'benefits.publishing.chosenDigital' : 'benefits.publishing.chosen',
          { value: formatKudos(chosen.unitValue) })
          : t(method === 'DIGITAL_PRIZE' ? 'benefits.publishing.hintDigital' : 'benefits.publishing.hint')}
      </p>
      {problem && <p className="text-destructive text-sm">{problem}</p>}
    </div>
  )
}
