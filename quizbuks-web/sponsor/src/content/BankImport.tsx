import { formatBytes, formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  notify,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import type { TFunction } from 'i18next'
import { Undo2, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { readImportBatches, undoImport, type BankLimits } from './api'
import { BatchImportError, importInBatches, leftOutOf, questionCountOf, splitQuestions } from './bank'
import { useEditing } from './editing'
import { QUIZZES_QUERY } from './paths'
import { reasonsOf } from './QuizTransfer'
import { checkImport, MissingFileData, readJsonFile, type ImportCheck } from './transfer'

/** The key of a quiz's import batches (under the quizzes' key: an edit reads them again). */
export const importBatchesQuery = (quizId: string) => [...QUIZZES_QUERY, 'imports', quizId] as const

type State =
  | { kind: 'checking' }
  | { kind: 'checked'; document: Record<string, unknown>; check: ImportCheck }
  | { kind: 'refused'; problems: string[] }
  | { kind: 'importing'; batch: number; batches: number; files: number; filesTotal: number }
  | { kind: 'done'; questions: number; batches: number; skipped: string[] }
  | { kind: 'failed'; batch: number; batches: number; imported: number; skipped: string[]; problems: string[] }

/** Why a batch failed, each reason with the step (one of its files, or importing it). */
function batchProblems(error: BatchImportError, t: TFunction): string[] {
  const { step } = error
  const where = step.kind === 'file'
    ? t('quizzes.transfer.stepFile', { index: step.index, total: step.total, key: step.key, type: step.type,
      size: formatBytes(step.size) })
    : t('bank.import.stepBatch')
  const reasons = error.cause instanceof MissingFileData
    ? [t('quizzes.transfer.missingData', { key: error.cause.key })]
    : reasonsOf(error.cause, t)
  return reasons.map((reason) => t('quizzes.transfer.failedAt', { step: where, reason }))
}

/**
 * F-S6b: "Import questions" into the open quiz. The server first checks the whole file (every problem at once); the
 * questions then go in batches of the bank's `importBatch`, each checked, its files uploaded and imported on its own
 * (`QB_QUIZ_IMPORT_QUESTIONS`). A refused batch stops there and says which and why: the batches before it stay.
 */
export function ImportQuestionsButton({ limits, count }: { limits: BankLimits | undefined
  /** The quiz's questions now. */
  count: number }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const editing = useEditing()
  const input = useRef<HTMLInputElement>(null)
  const [state, setState] = useState<State | null>(null)
  const size = limits?.importBatch ?? 0

  const chosen = async (file: File) => {
    setState({ kind: 'checking' })
    let document: Record<string, unknown>
    try {
      document = await readJsonFile(file)
    } catch {
      setState({ kind: 'refused', problems: [t('quizzes.transfer.notJson')] })
      return
    }
    try {
      setState({ kind: 'checked', document, check: await checkImport(document) })
    } catch (e) {
      setState({ kind: 'refused', problems: reasonsOf(e, t) })
    }
  }

  const start = async (document: Record<string, unknown>) => {
    const last: { revision: number | null } = { revision: null }
    try {
      const done = await importInBatches({
        quizId: editing.quizId, document, size, revision: editing.revision(),
        onProgress: (progress) => setState({ kind: 'importing', ...progress }),
        onImported: (output) => {
          last.revision = output.revision
        },
      })
      setState({ kind: 'done', questions: done.questions, batches: done.batches, skipped: done.skippedLanguages })
      notify.success(t('bank.import.done', { count: done.questions, batches: done.batches }))
    } catch (e) {
      if (e instanceof BatchImportError) {
        editing.failed(e.cause)
        setState({ kind: 'failed', batch: e.batch, batches: e.batches, imported: e.imported,
          skipped: e.skippedLanguages, problems: batchProblems(e, t) })
      } else {
        setState({ kind: 'refused', problems: reasonsOf(e, t) })
      }
    } finally {
      // The page reads the quiz once, after the last batch imported (not after each).
      if (last.revision !== null) await editing.edited({ revision: last.revision })
    }
  }

  const languages = (codes: string[]) => codes.map((l) => tQb(`languages.${l}`, { defaultValue: l })).join(' · ')
  const busy = state?.kind === 'checking' || state?.kind === 'importing'
  const skipped = state?.kind === 'done' || state?.kind === 'failed' ? state.skipped : []
  return (
    <>
      <input ref={input} type="file" accept="application/json,.json" className="sr-only" tabIndex={-1}
        aria-hidden data-testid="bank-import-file" onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) void chosen(file)
        }} />
      <Button variant="outline" disabled={editing.stale || !limits} data-testid="bank-import"
        onClick={() => input.current?.click()}>
        <Upload aria-hidden />
        {t('bank.import.button')}
      </Button>
      <Dialog open={state !== null} onOpenChange={(open) => {
        if (!open && !busy) setState(null)
      }}>
        <DialogContent className="max-h-[90vh] overflow-y-auto" data-testid="bank-import-dialog">
          <DialogHeader>
            <DialogTitle>{t('bank.import.title')}</DialogTitle>
            <DialogDescription>{t('bank.import.hint', { batch: size })}</DialogDescription>
          </DialogHeader>
          {state?.kind === 'checking' && <p role="status" className="text-sm">{t('bank.import.checking')}</p>}
          {state?.kind === 'checked' && (() => {
            const questions = questionCountOf(state.document)
            const left = leftOutOf(state.document)
            const max = limits?.maxQuestions ?? 0
            return (
              <div className="flex flex-col gap-2 text-sm" data-testid="bank-import-summary">
                <p>{t('bank.import.summary', { count: questions,
                  batches: splitQuestions(state.document, size).length, files: state.check.files.length })}</p>
                {left.materials > 0 && <p data-testid="bank-import-materials">
                  {t('bank.import.materialsLeft', { count: left.materials })}</p>}
                {left.products && <p>{t('bank.import.productsLeft')}</p>}
                {max > 0 && count + questions > max && (
                  <p className="text-destructive" data-testid="bank-import-too-many">
                    {t('bank.import.tooMany', { count, max })}</p>
                )}
                {state.check.files.length > 20 && <p className="text-muted-foreground">{t('quizzes.transfer.slow')}</p>}
              </div>
            )
          })()}
          {state?.kind === 'importing' && (
            <p role="status" className="text-sm" data-testid="bank-import-progress">
              {state.filesTotal > 0
                ? t('bank.import.progressFiles', { batch: state.batch, batches: state.batches, done: state.files,
                  total: state.filesTotal })
                : t('bank.import.progress', { batch: state.batch, batches: state.batches })}
            </p>
          )}
          {state?.kind === 'done' && (
            <p role="status" className="text-success text-sm" data-testid="bank-import-done">
              {t('bank.import.done', { count: state.questions, batches: state.batches })}
            </p>
          )}
          {(state?.kind === 'refused' || state?.kind === 'failed') && (
            <Alert variant="destructive" data-testid="bank-import-problems">
              <AlertDescription className="flex flex-col gap-1">
                <span>{state.kind === 'refused' ? t('bank.import.refused')
                  : t('bank.import.failed', { batch: state.batch, batches: state.batches, imported: state.imported })}</span>
                <ul className="list-disc pl-5">
                  {state.problems.map((problem, i) => <li key={i}>{problem}</li>)}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          {skipped.length > 0 && (
            <p className="text-muted-foreground text-sm" data-testid="bank-import-skipped">
              {t('bank.import.skipped', { languages: languages(skipped) })}
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setState(null)}>
              {state?.kind === 'done' || state?.kind === 'failed' ? t('bank.import.close') : t('common.cancel')}
            </Button>
            {state?.kind === 'checked' && (
              <Button data-testid="bank-import-confirm" disabled={editing.stale}
                onClick={() => void start(state.document)}>
                {t('bank.import.confirm')}
              </Button>
            )}
            {state?.kind === 'refused' && (
              <Button variant="outline" onClick={() => input.current?.click()}>{t('quizzes.transfer.choose')}</Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

/**
 * F-S6b: the batches imported into the quiz, newest first: when, how many, whether undone. "Undo" removes the batch's
 * questions not changed since (`QB_QUESTION_IMPORT_UNDO`) and says how many were removed and how many stayed.
 */
export function ImportBatches() {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const batches = useQuery({ queryKey: importBatchesQuery(editing.quizId),
    queryFn: () => readImportBatches(editing.quizId) })
  const [result, setResult] = useState<{ removed: number; kept: number } | null>(null)
  const items = batches.data ?? []
  if (items.length === 0) return null

  const undo = (importId: string) => undoImport(editing.quizId, importId, editing.revision()).then(
    async (output) => {
      const outcome = { removed: output.removed, kept: output.kept?.length ?? 0 }
      setResult(outcome)
      notify.success(t('bank.batches.undoneResult', outcome))
      await editing.edited(output)
    },
    (error: unknown) => {
      editing.failed(error)
      notify.error(reasonsOf(error, t).join(' '))
      throw error
    },
  )

  return (
    <section className="flex flex-col gap-2" aria-labelledby="bank-batches-title" data-testid="bank-batches">
      <h3 id="bank-batches-title" className="text-sm font-medium">{t('bank.batches.title')}</h3>
      {result && (
        <p role="status" className="text-sm" data-testid="bank-undo-result">{t('bank.batches.undoneResult', result)}</p>
      )}
      <ul className="flex flex-col gap-1">
        {items.map((batch) => {
          const time = formatDateTime(batch.importedAt)
          return (
            <li key={batch.importId} className="flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-sm"
              data-testid="bank-batch" data-undone={batch.undoneAt !== null}>
              <span>{t('bank.batches.at', { time })}</span>
              <Badge variant="outline">{t('bank.batches.count', { count: batch.questionCount })}</Badge>
              {batch.undoneAt ? (
                <span className="text-muted-foreground" data-testid="bank-batch-undone">
                  {t('bank.batches.undone', { time: formatDateTime(batch.undoneAt), removed: batch.removedCount ?? 0 })}
                </span>
              ) : (
                <ConfirmDialog destructive title={t('bank.batches.undoTitle')} description={t('bank.batches.undoText')}
                  confirmLabel={t('bank.batches.undo')} cancelLabel={t('common.cancel')}
                  onConfirm={() => undo(batch.importId)}
                  trigger={
                    <Button variant="outline" size="sm" className="ml-auto" disabled={editing.stale}
                      aria-label={t('bank.batches.undoLabel', { time })} data-testid="bank-batch-undo">
                      <Undo2 aria-hidden />
                      {t('bank.batches.undo')}
                    </Button>
                  } />
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
