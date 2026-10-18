import { Alert, AlertDescription, Button } from '@jabiz/ui'
import { useMutation } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { EXPORT_TARGETS, exportAnalytics, type ExportFormat, type ExportTarget } from './api'
import './messages'

/**
 * The export of a publication's analytics (E-7) through the platform's endpoint: the trend (the period shown), the
 * overview and the questions, each as CSV or Excel. Aggregates only; the server words its own refusals.
 */
export function ExportButtons({ params }: { params: Record<ExportTarget, Record<string, string> | null> }) {
  const { t } = useTranslation(SPONSOR)
  const exporting = useMutation({
    mutationFn: ({ target, format }: { target: ExportTarget; format: ExportFormat }) =>
      exportAnalytics(target, format, params[target]!),
  })
  return (
    <section className="flex flex-col gap-2" aria-labelledby="export-title" data-testid="analytics-export">
      <h2 id="export-title" className="text-base font-semibold">{t('analytics.export.label')}</h2>
      <p className="text-muted-foreground text-sm">{t('analytics.export.hint')}</p>
      <div className="flex flex-wrap gap-2">
        {EXPORT_TARGETS.flatMap((target) => (['csv', 'xlsx'] as const).map((format) => (
          <Button key={`${target}-${format}`} variant="outline" size="sm" data-testid={`export-${target}-${format}`}
            disabled={!params[target] || exporting.isPending}
            onClick={() => exporting.mutate({ target, format })}>
            <Download aria-hidden="true" />
            {t(`analytics.export.${format}`, { what: t(`analytics.export.${target}`) })}
          </Button>
        )))}
      </div>
      {exporting.isError && (
        <Alert variant="destructive" data-testid="export-error">
          <AlertDescription>{t('analytics.export.failed')} {problemText(exporting.error)}</AlertDescription>
        </Alert>
      )}
    </section>
  )
}
