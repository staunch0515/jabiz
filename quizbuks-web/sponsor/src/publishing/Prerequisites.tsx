import { Checkbox, Label, RadioGroup, RadioGroupItem } from '@jabiz/ui'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { PREREQUISITES, REQUIREMENTS, type Requirement } from '../qb/ids'
import { QB } from '../qb/messages'
import { SPONSOR } from '../messages'
import type { Prerequisite, PublicationRow } from './api'
import { useStatusLabel } from './shared'

/**
 * A draft's prerequisites (Q76, the wizard's step "Customer matching"): which of the sponsor's other submitted
 * publications a taker must first have passed, or answered. At most `PREREQUISITES.max`; the server checks the rest
 * (`QB_PREREQUISITE_*`). `candidates` are the sponsor's publications that can be required (not drafts, not this one).
 */
export function Prerequisites({ value, candidates, onChange, disabled }: {
  value: Prerequisite[]
  candidates: readonly PublicationRow[]
  onChange: (prerequisites: Prerequisite[]) => void
  disabled: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const statusLabel = useStatusLabel()
  const find = (publicationId: string) => value.find((p) => p.requiredPublicationId === publicationId)
  const full = value.length >= PREREQUISITES.max
  // Kept in the candidates' order (by name), so that the server's paths (prerequisites[i]) follow the page.
  const put = (publicationId: string, requirement: Requirement | null) => onChange(candidates.flatMap((candidate) => {
    if (candidate.publicationId !== publicationId) {
      const kept = find(candidate.publicationId)
      return kept ? [kept] : []
    }
    return requirement ? [{ requiredPublicationId: publicationId, requirement }] : []
  }))
  // A stored prerequisite whose publication is not among the candidates any more (none expected: a required
  // publication stays the sponsor's) is kept as it is.
  const orphans = value.filter((p) => !candidates.some((c) => c.publicationId === p.requiredPublicationId))

  return (
    <div className="flex flex-col gap-3" data-testid="publication-prerequisites">
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-medium">{t('publishing.prerequisites.title')}</h3>
        <p className="text-muted-foreground text-sm">{t('publishing.prerequisites.hint', { max: PREREQUISITES.max })}</p>
      </div>
      {candidates.length === 0 && orphans.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="prerequisites-none">
          {t('publishing.prerequisites.noCandidates')}
        </p>
      )}
      <ul className="flex flex-col gap-2">
        {candidates.map((candidate) => {
          const chosen = find(candidate.publicationId)
          return (
            <li key={candidate.publicationId} className="flex flex-col gap-2 rounded-md border p-3"
              data-testid="prerequisite" data-publication={candidate.publicationId} data-chosen={!!chosen}>
              <div className="flex items-center gap-2">
                <Checkbox id={`${id}-${candidate.publicationId}`} checked={!!chosen}
                  disabled={disabled || (!chosen && full)} data-testid="prerequisite-on"
                  onCheckedChange={(checked) => put(candidate.publicationId, checked === true ? 'PASSED' : null)} />
                <Label htmlFor={`${id}-${candidate.publicationId}`} className="font-normal">
                  {candidate.name}
                  <span className="text-muted-foreground ms-2 text-xs">{statusLabel(candidate.status)}</span>
                </Label>
              </div>
              {chosen && (
                <RadioGroup value={chosen.requirement} disabled={disabled} className="ms-6 flex flex-wrap gap-4"
                  aria-label={t('publishing.prerequisites.requirement', { name: candidate.name })}
                  onValueChange={(requirement) => put(candidate.publicationId, requirement as Requirement)}>
                  {REQUIREMENTS.map((requirement) => (
                    <div key={requirement} className="flex items-center gap-2">
                      <RadioGroupItem id={`${id}-${candidate.publicationId}-${requirement}`} value={requirement}
                        data-testid={`prerequisite-${requirement}`} />
                      <Label htmlFor={`${id}-${candidate.publicationId}-${requirement}`} className="font-normal">
                        {tQb(`prerequisite.${requirement}`)}
                      </Label>
                    </div>
                  ))}
                </RadioGroup>
              )}
            </li>
          )
        })}
        {orphans.map((orphan) => (
          <li key={orphan.requiredPublicationId} className="flex items-center gap-2 rounded-md border p-3"
            data-testid="prerequisite-orphan">
            <Checkbox id={`${id}-${orphan.requiredPublicationId}`} checked disabled={disabled}
              onCheckedChange={() => put(orphan.requiredPublicationId, null)} />
            <Label htmlFor={`${id}-${orphan.requiredPublicationId}`} className="font-normal">
              {orphan.requiredPublicationId} · {tQb(`prerequisite.${orphan.requirement}`)}
            </Label>
          </li>
        ))}
      </ul>
      {full && (
        <p className="text-muted-foreground text-sm" data-testid="prerequisites-full">
          {t('publishing.prerequisites.full', { max: PREREQUISITES.max })}
        </p>
      )}
    </div>
  )
}
