import { Button, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, ToggleGroup, ToggleGroupItem }
  from '@jabiz/ui'
import { Plus, Trash2 } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { PREREQUISITE } from '../qb/ids'
import type { Prerequisite, PublicationRow } from './api'

/**
 * A draft's prerequisites (Q76, docs/quizbuks/plans/Q76-prerequisites.md; the wizard's step "Customer matching"): the
 * sponsor's other submitted publications a taker passes or answers first, all of them, up to {@link PREREQUISITE.max}.
 * The page only offers the sponsor's submitted publications other than this one; the server checks everything again
 * (`QB_PREREQUISITE_*`), its problems shown at each row's `prerequisites[i]`.
 */
export function Prerequisites({ value, onChange, choices, disabled, problemOf }: {
  value: Prerequisite[]
  onChange: (next: Prerequisite[]) => void
  /** The sponsor's publications that may be required: submitted, not this one. */
  choices: PublicationRow[]
  disabled: boolean
  problemOf: (path: string) => string | undefined
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const put = (index: number, next: Prerequisite) => onChange(value.map((p, i) => (i === index ? next : p)))
  const byId = new Map(choices.map((choice) => [choice.publicationId, choice]))
  return (
    <div className="flex flex-col gap-3" data-testid="publication-prerequisites">
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-medium">{t('publishing.prerequisites.title')}</h3>
        <p className="text-muted-foreground text-sm">{t('publishing.prerequisites.hint', { max: PREREQUISITE.max })}</p>
      </div>
      {value.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="prerequisites-none">
          {t('publishing.prerequisites.none')}</p>
      )}
      <ol className="flex flex-col gap-3">
        {value.map((prerequisite, index) => {
          const rowId = `${id}-${index}`
          const problem = problemOf(`prerequisites[${index}].publicationId`)
          const chosen = byId.get(prerequisite.publicationId)
          // A row keeps the one stored even when it is no longer among the choices (shown by its id).
          const options = choices.filter((choice) => choice.publicationId === prerequisite.publicationId
            || !value.some((p) => p.publicationId === choice.publicationId))
          return (
            <li key={index} className="flex flex-col gap-2 rounded-md border p-3" data-testid="prerequisite-row">
              <div className="flex flex-wrap items-end gap-3">
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <Label id={`${rowId}-label`}>{t('publishing.prerequisites.publication', { number: index + 1 })}</Label>
                  <Select value={prerequisite.publicationId || undefined} disabled={disabled}
                    onValueChange={(publicationId) => put(index, { ...prerequisite, publicationId })}>
                    <SelectTrigger aria-labelledby={`${rowId}-label`} aria-invalid={problem ? true : undefined}
                      aria-describedby={problem ? `${rowId}-error` : undefined} className="w-full max-w-md"
                      data-testid="prerequisite-publication">
                      <SelectValue placeholder={t('publishing.prerequisites.choose')} />
                    </SelectTrigger>
                    <SelectContent>
                      {prerequisite.publicationId && !chosen && (
                        <SelectItem value={prerequisite.publicationId}>{prerequisite.publicationId}</SelectItem>
                      )}
                      {options.map((choice) => (
                        <SelectItem key={choice.publicationId} value={choice.publicationId}>{choice.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <Button type="button" variant="ghost" size="icon" disabled={disabled}
                  aria-label={t('publishing.prerequisites.remove', { number: index + 1 })}
                  data-testid="prerequisite-remove" onClick={() => onChange(value.filter((_, i) => i !== index))}>
                  <Trash2 aria-hidden />
                </Button>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-muted-foreground text-sm" id={`${rowId}-requirement`}>
                  {t('publishing.prerequisites.requirement')}</span>
                <ToggleGroup type="single" value={prerequisite.requirement} disabled={disabled} variant="outline"
                  size="sm" aria-labelledby={`${rowId}-requirement`} data-testid="prerequisite-requirement"
                  onValueChange={(requirement) => requirement && put(index, { ...prerequisite,
                    requirement: requirement as Prerequisite['requirement'] })}>
                  {(['PASSED', 'ANSWERED'] as const).map((requirement) => (
                    <ToggleGroupItem key={requirement} value={requirement} data-testid={`prerequisite-${requirement}`}>
                      {t(`publishing.prerequisites.${requirement}`)}
                    </ToggleGroupItem>
                  ))}
                </ToggleGroup>
              </div>
              {chosen?.audience === 'INVITED' && (
                <p className="text-muted-foreground text-sm" data-testid="prerequisite-invited">
                  {t('publishing.prerequisites.invitedOnly')}</p>
              )}
              {problem && <p id={`${rowId}-error`} className="text-destructive text-sm">{problem}</p>}
            </li>
          )
        })}
      </ol>
      {choices.length === 0 && value.length === 0
        ? <p className="text-muted-foreground text-sm">{t('publishing.prerequisites.noChoices')}</p>
        : (
          <Button type="button" variant="outline" size="sm" className="w-fit"
            disabled={disabled || value.length >= PREREQUISITE.max} data-testid="prerequisite-add"
            onClick={() => onChange([...value, { publicationId: '', requirement: 'PASSED' }])}>
            <Plus aria-hidden />
            {t('publishing.prerequisites.add')}
          </Button>
        )}
    </div>
  )
}
