import {
  Button,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
} from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Copy, Pause, Play, Trash2, Undo2 } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ProblemList } from '../content/editing'
import { SPONSOR } from '../messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import { navigate } from '../qb/router'
import {
  actionsOf,
  copyPublication,
  deletePublication,
  isStale,
  pausePublication,
  PUBLISHING_LIMITS,
  resumePublication,
  withdrawPublication,
  type Publication,
} from './api'
import { publicationPath, PUBLICATIONS_PATH, PUBLICATIONS_QUERY } from './paths'

/** A copy of a publication as a new draft (M-36), under a name of the sponsor's choice or the server's. */
function CopyDialog({ publication, open, onOpenChange }: {
  publication: Publication
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const id = useId()
  const [name, setName] = useState('')
  const copy = useMutation({
    mutationFn: () => copyPublication(publication.publicationId, name),
    onSuccess: async (output) => {
      onOpenChange(false)
      notify.success(t('publishing.actions.copied'))
      await queryClient.invalidateQueries({ queryKey: PUBLICATIONS_QUERY })
      navigate(publicationPath(output.publicationId))
    },
  })
  const problem = fieldProblem(copy.error, 'name')
  const general = generalProblem(copy.error, ['name'])
  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!copy.isPending) onOpenChange(next)
    }}>
      <DialogContent data-testid="publication-copy-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event: FormEvent) => {
          event.preventDefault()
          copy.mutate()
        }}>
          <DialogHeader>
            <DialogTitle>{t('publishing.actions.copyTitle')}</DialogTitle>
            <DialogDescription>{t('publishing.actions.copyText')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-name`}>{t('publishing.actions.copyName')}</Label>
            <Input id={`${id}-name`} value={name} maxLength={PUBLISHING_LIMITS.name} autoComplete="off"
              aria-invalid={problem ? true : undefined}
              aria-describedby={[`${id}-hint`, problem ? `${id}-error` : undefined].filter(Boolean).join(' ')}
              data-testid="publication-copy-name" onChange={(event) => setName(event.target.value)} />
            <p id={`${id}-hint`} className="text-muted-foreground text-sm">{t('publishing.actions.copyNameHint')}</p>
            {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
          </div>
          {general && <ProblemList problems={[general]} />}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={copy.isPending} onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={copy.isPending} aria-busy={copy.isPending || undefined}
              data-testid="publication-copy-confirm">
              {t('publishing.actions.copy')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * What can be done with a publication in its state, each asked first (M-35, M-36): withdraw from review, pause,
 * resume, copy, delete a draft. After a change the page reads the publication again; a page out of date is told
 * (`onStale`) rather than shown the refusal.
 */
export function PublicationActions({ publication, revision, disabled, onStale }: {
  publication: Publication
  /** The revision the page last saw (a draft's delete sends it). */
  revision: number
  disabled?: boolean
  onStale: () => void
}) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [copying, setCopying] = useState(false)
  const can = actionsOf(publication.status)
  const id = publication.publicationId

  /** Runs a change; afterwards the lists and this page read again. A failure keeps the confirmation open. */
  const change = (run: () => Promise<unknown>, done: string, after?: () => void) => run().then(
    async () => {
      notify.success(done)
      after?.()
      await queryClient.invalidateQueries({ queryKey: PUBLICATIONS_QUERY })
    },
    (error: unknown) => {
      if (isStale(error)) {
        onStale()
        return
      }
      notify.error(problemText(error))
      throw error
    },
  )

  return (
    <div className="flex flex-wrap gap-2" role="group" aria-label={t('publishing.actions.label')}>
      {can.withdraw && (
        <ConfirmDialog title={t('publishing.actions.withdrawTitle')} description={t('publishing.actions.withdrawText')}
          confirmLabel={t('publishing.actions.withdraw')} cancelLabel={t('common.cancel')}
          onConfirm={() => change(() => withdrawPublication(id), t('publishing.actions.withdrawn'))}
          trigger={
            <Button variant="outline" disabled={disabled} data-testid="publication-withdraw">
              <Undo2 aria-hidden />
              {t('publishing.actions.withdraw')}
            </Button>
          } />
      )}
      {can.pause && (
        <ConfirmDialog title={t('publishing.actions.pauseTitle')} description={t('publishing.actions.pauseText')}
          confirmLabel={t('publishing.actions.pause')} cancelLabel={t('common.cancel')}
          onConfirm={() => change(() => pausePublication(id), t('publishing.actions.paused'))}
          trigger={
            <Button variant="outline" disabled={disabled} data-testid="publication-pause">
              <Pause aria-hidden />
              {t('publishing.actions.pause')}
            </Button>
          } />
      )}
      {can.resume && (
        <ConfirmDialog title={t('publishing.actions.resumeTitle')} description={t('publishing.actions.resumeText')}
          confirmLabel={t('publishing.actions.resume')} cancelLabel={t('common.cancel')}
          onConfirm={() => change(() => resumePublication(id), t('publishing.actions.resumed'))}
          trigger={
            <Button variant="outline" disabled={disabled} data-testid="publication-resume">
              <Play aria-hidden />
              {t('publishing.actions.resume')}
            </Button>
          } />
      )}
      {can.copy && (
        <Button variant="outline" disabled={disabled} data-testid="publication-copy" onClick={() => setCopying(true)}>
          <Copy aria-hidden />
          {t('publishing.actions.copy')}
        </Button>
      )}
      {can.delete && (
        <ConfirmDialog destructive title={t('publishing.actions.deleteTitle')}
          description={t('publishing.actions.deleteText')} confirmLabel={t('publishing.actions.delete')}
          cancelLabel={t('common.cancel')}
          onConfirm={() => change(() => deletePublication(id, revision), t('publishing.actions.deleted'),
            () => navigate(PUBLICATIONS_PATH))}
          trigger={
            <Button variant="outline" disabled={disabled} data-testid="publication-delete">
              <Trash2 aria-hidden />
              {t('publishing.actions.delete')}
            </Button>
          } />
      )}
      {copying && <CopyDialog publication={publication} open onOpenChange={setCopying} />}
    </div>
  )
}
