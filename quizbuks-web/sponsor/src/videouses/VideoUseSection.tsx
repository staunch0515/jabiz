import {
  Alert,
  AlertDescription,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
  RadioGroup,
  RadioGroupItem,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import { titleIn } from '../sharedvideos/api'
import {
  NOTE_LENGTH,
  PRICINGS,
  readVideoUse,
  saveVideoUse,
  submitVideoUse,
  videoUseQuery,
  type Pricing,
  type VideoUseRow,
} from './api'
import { FiguresGrid, UseSummary, VideoUseBadge } from './parts'
import './messages'

const FIELDS = ['periodFrom', 'periodTo', 'pricing', 'price', 'note'] as const

/** The first day of an instant, in the deployment's zone (Japan), as `yyyy-mm-dd`. */
function dayOf(instant: string | null): string {
  if (!instant) return ''
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit',
    day: '2-digit' }).format(new Date(instant))
}

/**
 * F-S14 (docs/quizbuks/plans/impl/F-S14.md): on a publication's page, the ledger of the other Sponsor's shared video
 * it uses (the user's side): the figures counted every day (final once it ended; each below the minimum group "fewer
 * than"), the entry with its state, the provider's reason when sent back, writing and correcting it, and sending it to
 * the provider. Nothing for a publication using its own videos.
 */
export function VideoUseSection({ publicationId }: { publicationId: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const row = useQuery({ queryKey: videoUseQuery(publicationId), queryFn: () => readVideoUse(publicationId) })
  const submit = useMutation({
    mutationFn: (use: { videoUseId: string; revision: number }) => submitVideoUse(use.videoUseId, use.revision),
    onSuccess: async () => {
      notify.success(t('videoUses.submitted'))
      await queryClient.invalidateQueries({ queryKey: videoUseQuery(publicationId) })
    },
    onError: (error) => notify.error(problemText(error)),
  })
  if (!row.data) return null
  const data = row.data
  const use = data.use
  const company = data.otherCompany ?? t('videoUses.company')
  return (
    <section className="flex flex-col gap-3" aria-labelledby="video-use-title" data-testid="video-use-section">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="video-use-title" className="text-sm font-medium">{t('videoUses.title')}</h2>
        {use && <VideoUseBadge status={use.status} />}
      </div>
      <p className="text-muted-foreground text-sm">
        {t('videoUses.hint', { company, video: titleIn(data.videoTitles, i18n.language) })}</p>
      <FiguresGrid figures={data.figures} testId="video-use-figures" />
      {!use && <p className="text-sm" data-testid="video-use-none">{t('videoUses.none')}</p>}
      {use && (
        <>
          {use.status === 'REJECTED' && use.rejectReason && (
            <Alert data-testid="video-use-reason">
              <AlertDescription className="whitespace-pre-wrap">
                {t('videoUses.sentBack', { reason: use.rejectReason })}</AlertDescription>
            </Alert>
          )}
          {use.status === 'DRAFT' && use.rejectReason && (
            <p className="text-muted-foreground text-sm whitespace-pre-wrap" data-testid="video-use-reason">
              {t('videoUses.sentBack', { reason: use.rejectReason })}</p>
          )}
          {use.status === 'SUBMITTED' && <p className="text-sm" data-testid="video-use-waiting">{t('videoUses.waiting')}</p>}
          {use.status === 'CONFIRMED' && use.confirmedRevision != null && (
            <p className="text-sm" data-testid="video-use-confirmed">
              {t('videoUses.confirmedNote', { revision: use.confirmedRevision })}</p>
          )}
          <UseSummary row={data} use={use} />
        </>
      )}
      <div className="flex flex-wrap gap-2">
        {(!use || use.status !== 'SUBMITTED') && (
          <Button variant={use ? 'outline' : 'default'} size="sm" onClick={() => setEditing(true)}
            data-testid="video-use-edit">{use ? t('videoUses.correct') : t('videoUses.write')}</Button>
        )}
        {use?.status === 'DRAFT' && (
          <Button size="sm" disabled={submit.isPending} onClick={() => submit.mutate(use)}
            data-testid="video-use-submit">{t('videoUses.submit')}</Button>
        )}
      </div>
      {editing && <VideoUseDialog row={data} onClose={() => setEditing(false)} onSaved={async () => {
        setEditing(false)
        notify.success(t('videoUses.saved'))
        await queryClient.invalidateQueries({ queryKey: videoUseQuery(publicationId) })
      }} />}
    </section>
  )
}

/** Writing or correcting the entry: the period (the publication's dates first), the pricing, the price, a note. */
function VideoUseDialog({ row, onClose, onSaved }: { row: VideoUseRow; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const use = row.use
  const [form, setForm] = useState({
    periodFrom: use?.periodFrom ?? dayOf(row.wentOnAt),
    periodTo: use?.periodTo ?? dayOf(row.endedAt ?? row.endAt),
    pricing: (use?.pricing ?? 'FIXED') as Pricing,
    price: use ? String(use.price) : '',
    note: use?.note ?? '',
  })
  const set = <K extends keyof typeof form>(key: K, value: (typeof form)[K]) =>
    setForm((current) => ({ ...current, [key]: value }))
  const save = useMutation({
    mutationFn: () => saveVideoUse({ ...form, publicationId: row.publicationId, videoUseId: use?.videoUseId,
      baseRevision: use?.revision }),
    onSuccess: onSaved,
  })
  const problem = (field: string) => fieldProblem(save.error, field)
  const error = (field: string) => problem(field) && <p className="text-destructive text-sm">{problem(field)}</p>
  const general = generalProblem(save.error, FIELDS)
  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate()
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="sm:max-w-lg" data-testid="video-use-dialog">
        <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{use ? t('videoUses.correct') : t('videoUses.write')}</DialogTitle>
            <DialogDescription>
              {use?.status === 'CONFIRMED' ? t('videoUses.correctingConfirmed') : t('videoUses.title')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-wrap gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-from`}>{t('videoUses.periodFrom')}</Label>
              <Input id={`${id}-from`} type="date" className="w-44" value={form.periodFrom}
                aria-invalid={problem('periodFrom') ? true : undefined} data-testid="video-use-from"
                onChange={(event) => set('periodFrom', event.target.value)} />
              {error('periodFrom')}
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-to`}>{t('videoUses.periodTo')}</Label>
              <Input id={`${id}-to`} type="date" className="w-44" value={form.periodTo}
                aria-invalid={problem('periodTo') ? true : undefined} data-testid="video-use-to"
                onChange={(event) => set('periodTo', event.target.value)} />
              {error('periodTo')}
            </div>
          </div>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('videoUses.pricingLabel')}</legend>
            <RadioGroup value={form.pricing} onValueChange={(value) => set('pricing', value as Pricing)}>
              {PRICINGS.map((pricing) => (
                <div key={pricing} className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-pricing-${pricing}`} value={pricing}
                    data-testid={`video-use-pricing-${pricing}`} />
                  <Label htmlFor={`${id}-pricing-${pricing}`} className="font-normal">
                    {t(`videoUses.pricing.${pricing}`)}</Label>
                </div>
              ))}
            </RadioGroup>
            {error('pricing')}
          </fieldset>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-price`}>{t('videoUses.price')}</Label>
            <Input id={`${id}-price`} inputMode="numeric" className="w-44" value={form.price}
              aria-invalid={problem('price') ? true : undefined} data-testid="video-use-price"
              onChange={(event) => set('price', event.target.value)} />
            {error('price')}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('videoUses.note')}</Label>
            <Textarea id={`${id}-note`} rows={3} value={form.note} maxLength={NOTE_LENGTH}
              onChange={(event) => set('note', event.target.value)} data-testid="video-use-note-input" />
          </div>
          {general && <Alert variant="destructive" data-testid="video-use-error"><AlertDescription>{general}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('videoUses.cancel')}</Button>
            <Button type="submit" disabled={save.isPending} data-testid="video-use-save">{t('videoUses.save')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
