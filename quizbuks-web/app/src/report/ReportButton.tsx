import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  RadioGroup,
  RadioGroupItem,
  Textarea,
  notify,
} from '@jabiz/ui'
import { useMutation } from '@tanstack/react-query'
import { Flag } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { problemText } from '../qb/problems'
import { reportQuiz } from './api'
import { REPORT_NOTE_LENGTH, REPORT_REASONS, type ReportReason } from './ids'

/**
 * "Report this quiz" (Q49a; Apple App Review Guideline 1.2): a reason and an optional note for the quiz's reviewers
 * (headquarters, or the operator who looks after its sponsor). The server decides whether the report is taken: one open
 * report of the caller per quiz, so many a day.
 */
export function ReportButton({ publicationId, name }: { publicationId: string; name: string }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState<ReportReason | ''>('')
  const [note, setNote] = useState('')
  const send = useMutation({
    mutationFn: () => reportQuiz(publicationId, reason as ReportReason, note),
    onSuccess: () => {
      notify.success(t('report.sent'))
      setOpen(false)
      setReason('')
      setNote('')
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (reason) send.mutate()
  }
  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) send.reset() }}>
      <Button type="button" variant="ghost" size="sm" className="text-muted-foreground self-start"
        onClick={() => setOpen(true)} data-testid="report-open">
        <Flag aria-hidden className="size-4" />
        {t('report.open')}
      </Button>
      <DialogContent data-testid="report-dialog">
        <form className="flex flex-col gap-4" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>{t('report.title')}</DialogTitle>
            <DialogDescription>{t('report.hint', { name })}</DialogDescription>
          </DialogHeader>
          <RadioGroup value={reason} onValueChange={(value) => setReason(value as ReportReason)}
            aria-label={t('report.reason')} data-testid="report-reasons">
            {REPORT_REASONS.map((code) => (
              <div key={code} className="flex items-center gap-2">
                <RadioGroupItem id={`${id}-${code}`} value={code} data-testid={`report-reason-${code}`} />
                <Label htmlFor={`${id}-${code}`} className="font-normal">{t(`report.reasons.${code}`)}</Label>
              </div>
            ))}
          </RadioGroup>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('report.note')}</Label>
            <Textarea id={`${id}-note`} value={note} maxLength={REPORT_NOTE_LENGTH} rows={3}
              onChange={(event) => setNote(event.target.value)} data-testid="report-note" />
          </div>
          {send.isError && (
            <p className="text-destructive text-sm" role="alert" data-testid="report-error">
              {problemText(send.error)}</p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t('report.cancel')}</Button>
            <Button type="submit" disabled={!reason || send.isPending} data-testid="report-send">
              {t('report.send')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
