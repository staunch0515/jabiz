import {
  Alert,
  AlertDescription,
  Button,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  notify,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import { titleIn } from '../sharedvideos/api'
import {
  confirmVideoUse,
  PROVIDED_QUERY,
  readProvidedUses,
  REASON_LENGTH,
  rejectVideoUse,
  type VideoUse,
  type VideoUseRow,
} from './api'
import { FiguresGrid, period, UseSummary, VideoUseBadge } from './parts'
import './messages'

/**
 * F-S14 (docs/quizbuks/plans/impl/F-S14.md): the provider's side of the ledger, under "My shared videos": the
 * publications of other Sponsors using the Sponsor's shared videos, each with the figures (the same as the user's)
 * and the entry its Sponsor sent, confirmed as read or sent back with a reason.
 */
export function VideoUsesProvided() {
  const { t } = useTranslation(SPONSOR)
  const rows = useQuery({ queryKey: PROVIDED_QUERY, queryFn: () => readProvidedUses() })
  return (
    <section className="flex flex-col gap-3" aria-labelledby="video-uses-provided-title" data-testid="video-uses-provided">
      <h2 id="video-uses-provided-title" className="text-lg font-semibold">{t('videoUses.providedTitle')}</h2>
      <p className="text-muted-foreground text-sm">{t('videoUses.providedHint')}</p>
      {rows.isError && <p className="text-destructive text-sm" role="alert">{problemText(rows.error)}</p>}
      {rows.data && rows.data.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="video-uses-provided-empty">{t('videoUses.providedEmpty')}</p>
      )}
      {rows.data && rows.data.length > 0 && (
        <ul className="flex flex-col gap-3">
          {rows.data.map((row) => <ProvidedUse key={row.publicationId} row={row} />)}
        </ul>
      )}
    </section>
  )
}

function ProvidedUse({ row }: { row: VideoUseRow }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [rejecting, setRejecting] = useState<VideoUse | null>(null)
  const use = row.use
  const refresh = () => queryClient.invalidateQueries({ queryKey: PROVIDED_QUERY })
  const confirm = (answered: VideoUse) => confirmVideoUse(answered.videoUseId, answered.revision).then(async () => {
    notify.success(t('videoUses.confirmed'))
    await refresh()
  }, (problem) => {
    notify.error(problemText(problem))
    throw problem
  })
  return (
    <li className="flex flex-col gap-3 rounded-lg border p-3" data-testid="video-use-provided"
      data-publication={row.publicationId}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{row.name}</span>
        {use && use.submittedAt ? <VideoUseBadge status={use.status} />
          : <span className="text-muted-foreground text-sm" data-testid="video-use-not-sent">{t('videoUses.notSent')}</span>}
      </div>
      <p className="text-muted-foreground text-sm">
        {titleIn(row.videoTitles, i18n.language)}
        {' · '}{t('videoUses.otherSide', { company: row.otherCompany ?? t('videoUses.noCompany') })}
      </p>
      <FiguresGrid figures={row.figures} testId="video-use-provided-figures" />
      {use && (
        <>
          {use.status === 'REJECTED' && use.rejectReason && (
            <p className="text-sm whitespace-pre-wrap" data-testid="video-use-reason">
              {t('videoUses.sentBack', { reason: use.rejectReason })}</p>
          )}
          {use.status === 'CONFIRMED' && use.confirmedRevision != null && (
            <p className="text-sm" data-testid="video-use-confirmed">
              {t('videoUses.confirmedNote', { revision: use.confirmedRevision })}</p>
          )}
          <UseSummary row={row} use={use} />
          {use.status === 'SUBMITTED' && (
            <div className="flex flex-wrap gap-2">
              <ConfirmDialog title={t('videoUses.confirmTitle')}
                description={t('videoUses.confirmText', { revision: use.revision,
                  pricing: t(`videoUses.pricing.${use.pricing}`), period: period(use) })}
                confirmLabel={t('videoUses.confirm')} cancelLabel={t('videoUses.cancel')}
                onConfirm={() => confirm(use)}
                trigger={<Button size="sm" aria-label={t('videoUses.confirmNamed', { name: row.name })}
                  data-testid="video-use-confirm">{t('videoUses.confirm')}</Button>} />
              <Button size="sm" variant="outline" aria-label={t('videoUses.rejectNamed', { name: row.name })}
                onClick={() => setRejecting(use)} data-testid="video-use-reject">{t('videoUses.reject')}</Button>
            </div>
          )}
        </>
      )}
      {rejecting && <RejectDialog use={rejecting} onClose={() => setRejecting(null)} onDone={async () => {
        setRejecting(null)
        notify.success(t('videoUses.rejected'))
        await refresh()
      }} />}
    </li>
  )
}

/** Sending an entry back: the reason is required (the Sponsor corrects by it). */
function RejectDialog({ use, onClose, onDone }: { use: VideoUse; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [reason, setReason] = useState('')
  const reject = useMutation({ mutationFn: () => rejectVideoUse(use.videoUseId, reason, use.revision), onSuccess: onDone })
  const problem = fieldProblem(reject.error, 'reason')
  const general = generalProblem(reject.error, ['reason'])
  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    reject.mutate()
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="sm:max-w-lg" data-testid="video-use-reject-dialog">
        <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('videoUses.rejectTitle')}</DialogTitle>
            <DialogDescription>{t('videoUses.rejectText')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-reason`}>{t('videoUses.reason')}</Label>
            <Textarea id={`${id}-reason`} rows={3} value={reason} maxLength={REASON_LENGTH} required
              aria-invalid={problem ? true : undefined} onChange={(event) => setReason(event.target.value)}
              data-testid="video-use-reason-input" />
            {problem && <p className="text-destructive text-sm">{problem}</p>}
          </div>
          {general && <Alert variant="destructive"><AlertDescription>{general}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('videoUses.cancel')}</Button>
            <Button type="submit" disabled={reject.isPending || reason.trim() === ''}
              data-testid="video-use-reject-send">{t('videoUses.reject')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
