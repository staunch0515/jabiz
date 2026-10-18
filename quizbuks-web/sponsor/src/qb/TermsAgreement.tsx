import { Checkbox, Label } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { LegalLink } from './ConsentGate'
import type { ConsentState, LegalKind } from './legal'
import { QB } from './messages'

/**
 * The versions a form accepts with registration's second step or a sponsor application (`termsVersionNo`,
 * `privacyVersionNo`): those in effect that the page showed. The server refuses others (`QB_TERMS_OUTDATED`).
 */
export function acceptedVersions(rows: readonly ConsentState[] | undefined):
  { termsVersionNo: number | null; privacyVersionNo: number | null } {
  const of = (kinds: LegalKind[]) => rows?.find((row) => kinds.includes(row.kind))?.versionNo ?? null
  return { termsVersionNo: of(['TERMS_APP', 'TERMS_SPONSOR']), privacyVersionNo: of(['PRIVACY']) }
}

/**
 * "I agree to the terms" with links to the texts in effect (each opens in a new tab), for registration's second step
 * (Q7-3) and the sponsor application. `rows` are the caller's standing (`qb.me.consents`): the texts in effect.
 */
export function TermsAgreement({ id, kinds, rows, checked, onChange, invalid, describedBy, testId }: {
  id: string
  kinds: readonly LegalKind[]
  rows: readonly ConsentState[]
  checked: boolean
  onChange: (checked: boolean) => void
  invalid?: boolean
  describedBy?: string
  testId: string
}) {
  const { t } = useTranslation(QB)
  const shown = kinds.filter((kind) => rows.some((row) => row.kind === kind))
  return (
    <div className="flex flex-col gap-2" data-testid={`${testId}-block`}>
      <div className="flex items-start gap-2">
        <Checkbox id={id} checked={checked} data-testid={testId} aria-invalid={invalid}
          aria-describedby={[describedBy, shown.length > 0 ? `${id}-links` : undefined].filter(Boolean).join(' ')
            || undefined}
          onCheckedChange={(value) => onChange(value === true)} />
        <Label htmlFor={id} className="leading-snug">
          {shown.length > 0 ? t('consent.agree') : t('consent.agreeNone')}
        </Label>
      </div>
      {shown.length > 0 && (
        <p id={`${id}-links`} className="flex flex-wrap gap-x-4 gap-y-1 pl-6 text-sm">
          {shown.map((kind) => <LegalLink key={kind} kind={kind} />)}
        </p>
      )}
    </div>
  )
}
