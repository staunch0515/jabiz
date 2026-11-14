import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Button,
  PageState,
} from '@jabiz/admin'
import { currentMonthInJapan } from './account-api'
import { LoadFailed } from './components'
import { usePoints } from './hooks'
import { readOperatorSponsorAi } from './operator-api'

/**
 * One sponsor's AI credit and the AI fee of this month charged under the operator (F-M9 b2; `qb.operator.sponsor-ai`):
 * read only, the figures as the server gives them. The month is the page's default (Japan's, as the plans' months are;
 * the platform has no server date to read, F-M12's known trade-offs), and the server counts the fee by the fiscal year's
 * zone; the month is written out so that a reader sees which month the figure is for.
 */
export function SponsorAiDialog({ sponsorId, company, onClose }: { sponsorId: string; company: string; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const points = usePoints()
  const month = currentMonthInJapan()
  const ai = useQuery({ queryKey: ['qb-ext', 'operator-sponsor-ai', sponsorId, month],
    queryFn: () => readOperatorSponsorAi(sponsorId, month) })
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="sponsor-ai-dialog">
        <DialogHeader>
          <DialogTitle>{t('operator.sponsorAi.title', { name: company })}</DialogTitle>
          <DialogDescription>{t('operator.sponsorAi.hint')}</DialogDescription>
        </DialogHeader>
        {ai.isError ? <LoadFailed error={ai.error} onRetry={() => void ai.refetch()} />
          : ai.isPending ? <PageState kind="loading" />
            : !ai.data ? <p className="text-muted-foreground text-sm" data-testid="sponsor-ai-none">{t('operator.sponsorAi.none')}</p>
              : (
                <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2" data-testid="sponsor-ai-values">
                  <dt className="text-muted-foreground">{t('operator.sponsorAi.credit')}</dt>
                  <dd className="text-right tabular-nums" data-testid="sponsor-ai-credit">{points(ai.data.aiCredit)}</dd>
                  <dt className="text-muted-foreground">{t('operator.sponsorAi.monthFee', { month })}</dt>
                  <dd className="text-right tabular-nums" data-testid="sponsor-ai-fee">{points(ai.data.monthFee)}</dd>
                  <dt className="text-muted-foreground">{t('operator.sponsorAi.monthJobs', { month })}</dt>
                  <dd className="text-right tabular-nums" data-testid="sponsor-ai-jobs">{ai.data.monthJobs}</dd>
                </dl>
              )}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('operator.sponsorAi.close')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
