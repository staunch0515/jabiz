import { Eye } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'

/** The fields whose place on the taker's screens the console names (Q34b, docs/quizbuks/plans/Q34-ui-polish.md). */
export type ShownField = 'quizTitle' | 'intro' | 'cover' | 'materialTitle' | 'materialDescription' | 'materialBody'
  | 'publicationName' | 'prizeDescription' | 'sponsorIntro'

/**
 * Where a field appears for takers (Q34b): a small line under it, so a sponsor knows what each text is for before
 * looking at the preview.
 */
export function ShownAt({ field }: { field: ShownField }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <p className="text-muted-foreground flex items-start gap-1.5 text-xs" data-testid={`shown-at-${field}`}>
      <Eye aria-hidden className="mt-px size-3.5 shrink-0" />
      <span>{t('shownAt.prefix')} {t(`shownAt.${field}`)}</span>
    </p>
  )
}
