import { ApiError, formatBytes } from '@jabiz/client'
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
  notify,
} from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { Download, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { QB } from '../qb/messages'
import { SPONSOR } from '../messages'
import { navigate } from '../qb/router'
import { quizPath, QUIZZES_QUERY } from './paths'
import {
  checkImport,
  CorruptFileData,
  download,
  exportFile,
  exportQuiz,
  importQuiz,
  ImportStepError,
  MissingFileData,
  readConfirmBytes,
  readJsonFile,
  type ExportFile,
  type ImportCheck,
} from './transfer'

/**
 * A quiz's "Export" (Q16; docs/quizbuks/plans/Q16-quiz-transfer.md): the draft and every file it uses into one JSON
 * file the browser downloads. Fetching the files shows how far it got; a file over `qb.package.confirm-bytes` (100 MB
 * by default) is downloaded after asking.
 */
export function ExportQuizButton({ quizId, title }: { quizId: string; title: string }) {
  const { t } = useTranslation(SPONSOR)
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  const [large, setLarge] = useState<ExportFile | null>(null)

  const run = async () => {
    setProgress({ done: 0, total: 0 })
    try {
      const [document, confirmBytes] = await Promise.all([
        exportQuiz(quizId, (done, total) => setProgress({ done, total })), readConfirmBytes()])
      if (!document) throw new Error('not found')
      // Written once: the size asked about is the file downloaded; above qb.package.confirm-bytes it asks first.
      const file = exportFile(document)
      if (file.size > confirmBytes) setLarge(file)
      else {
        download(file)
        notify.success(t('quizzes.transfer.exported'))
      }
    } catch {
      notify.error(t('quizzes.transfer.exportFailed'))
    } finally {
      setProgress(null)
    }
  }

  return (
    <>
      <Button variant="outline" size="sm" disabled={progress !== null} aria-busy={progress !== null || undefined}
        aria-label={t('quizzes.transfer.exportNamed', { title })} data-testid="quiz-export"
        onClick={() => void run()}>
        <Download aria-hidden />
        {progress && progress.total > 0
          ? t('quizzes.transfer.exporting', progress)
          : t('quizzes.transfer.export')}
      </Button>
      <ConfirmDialog open={large !== null} onOpenChange={(open) => !open && setLarge(null)}
        title={t('quizzes.transfer.largeTitle')}
        description={large ? t('quizzes.transfer.largeText', { size: formatBytes(large.size) }) : undefined}
        confirmLabel={t('quizzes.transfer.largeConfirm')} cancelLabel={t('quizzes.transfer.cancel')}
        onConfirm={() => {
          if (large) download(large)
          setLarge(null)
        }} />
    </>
  )
}

type ImportState =
  | { kind: 'checking' }
  | { kind: 'checked'; document: Record<string, unknown>; check: ImportCheck }
  | { kind: 'refused'; problems: string[] }
  | { kind: 'importing'; done: number; total: number }
  | { kind: 'failed'; problems: string[] }

/**
 * The lines a refusal lists, each saying why and, for an import that failed after the check, at which step (which
 * file, or making the draft): "Something went wrong" alone leaves the sponsor nothing to change.
 */
function problemsOf(error: unknown, t: TFunction): string[] {
  if (error instanceof MissingFileData) return [t('quizzes.transfer.missingData', { key: error.key })]
  if (!(error instanceof ImportStepError)) return reasonsOf(error, t)
  const { step } = error
  const where = step.kind === 'file'
    ? t('quizzes.transfer.stepFile', { index: step.index, total: step.total, key: step.key, type: step.type,
      size: formatBytes(step.size) })
    : t('quizzes.transfer.stepDraft')
  return reasonsOf(error.cause, t).map((reason) => t('quizzes.transfer.failedAt', { step: where, reason }))
}

/** Why a call failed: the server's own words where it gave them, else what its status or the browser's error means. */
export function reasonsOf(error: unknown, t: TFunction): string[] {
  if (error instanceof CorruptFileData) return [t('quizzes.transfer.reason.corrupt', { key: error.key })]
  if (error instanceof ApiError) {
    if (error.violations.length > 0) {
      return error.violations.map((v) => (v.field ? `${v.field}: ${v.message}` : v.message))
    }
    const { status } = error
    if (status === 401) return [t('quizzes.transfer.reason.signedOut')]
    if (status === 413) return [t('quizzes.transfer.reason.tooLarge')]
    if (status === 429) return [t('quizzes.transfer.reason.rateLimited')]
    if (status >= 500) return [t('quizzes.transfer.reason.server', { status })]
    // A 4xx the server explained (its detail is in the request's language), else its status.
    if (!/^HTTP \d+$/.test(error.message)) return [t('quizzes.transfer.reason.refused', { text: error.message, status })]
    return [t('quizzes.transfer.reason.http', { status })]
  }
  // fetch rejects with a TypeError when the server cannot be reached at all.
  if (error instanceof TypeError) return [t('quizzes.transfer.reason.network')]
  return [error instanceof Error && error.message ? error.message : t('quizzes.transfer.reason.unknown')]
}

/**
 * "Import" (Q16): a quiz file becomes a new draft. The server first says which version of the format reads the file,
 * what it makes and which files it needs, or every reason it cannot be imported; the files are then uploaded through
 * the content's policies and the draft made in one step, and the editor opens on it.
 */
export function ImportQuizButton() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [state, setState] = useState<ImportState | null>(null)

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
      setState({ kind: 'refused', problems: problemsOf(e, t) })
    }
  }

  const start = async (document: Record<string, unknown>, check: ImportCheck) => {
    setState({ kind: 'importing', done: 0, total: check.files.length })
    try {
      const imported = await importQuiz(document, check, (done, total) => setState({ kind: 'importing', done, total }))
      setState(null)
      notify.success(t('quizzes.transfer.imported'))
      await queryClient.invalidateQueries({ queryKey: QUIZZES_QUERY })
      navigate(quizPath(imported.quizId))
    } catch (e) {
      setState({ kind: 'failed', problems: problemsOf(e, t) })
    }
  }

  const busy = state?.kind === 'checking' || state?.kind === 'importing'
  return (
    <>
      <input ref={input} type="file" accept="application/json,.json" className="sr-only" tabIndex={-1}
        aria-hidden data-testid="quiz-import-file" onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) void chosen(file)
        }} />
      <Button variant="outline" data-testid="quiz-import" onClick={() => input.current?.click()}>
        <Upload aria-hidden />
        {t('quizzes.transfer.import')}
      </Button>
      <Dialog open={state !== null} onOpenChange={(open) => {
        if (!open && !busy) setState(null)
      }}>
        <DialogContent className="max-h-[90vh] overflow-y-auto" data-testid="quiz-import-dialog">
          <DialogHeader>
            <DialogTitle>{t('quizzes.transfer.importTitle')}</DialogTitle>
            <DialogDescription>{t('quizzes.transfer.importHint')}</DialogDescription>
          </DialogHeader>
          {state?.kind === 'checking' && <p role="status" className="text-sm">{t('quizzes.transfer.checking')}</p>}
          {state?.kind === 'checked' && (
            <div className="flex flex-col gap-2 text-sm" data-testid="quiz-import-summary">
              <p>{t('quizzes.transfer.summary', { title: state.check.title, questions: state.check.questions,
                materials: state.check.materials, files: state.check.files.length })}</p>
              {state.check.kind === 'SURVEY' && <p>{t('quizzes.transfer.survey')}</p>}
              {(state.check.languages?.length ?? 0) > 0 && state.check.language && (
                <p data-testid="quiz-import-languages">{t('quizzes.transfer.languages', {
                  languages: [state.check.language, ...state.check.languages].map((l) => tQb(`languages.${l}`))
                    .join(' · ') })}</p>
              )}
              <p className="text-muted-foreground" data-testid="quiz-import-version">
                {t('quizzes.transfer.version', { version: state.check.formatVersion,
                  current: state.check.currentVersion })}
                {state.check.readers.length > 1 && ` ${t('quizzes.transfer.upgraded', {
                  readers: state.check.readers.join(' → ') })}`}
              </p>
              {(state.check.products ?? 0) > 0 && (
                <p data-testid="quiz-import-products">{t('quizzes.transfer.products', { count: state.check.products })}</p>
              )}
              {(state.check.missingProducts?.length ?? 0) > 0 && (
                <p className="text-muted-foreground" data-testid="quiz-import-missing-products">
                  {t('quizzes.transfer.missingProducts', { products: state.check.missingProducts!.join('、') })}
                </p>
              )}
              {state.check.files.length > 20 && <p className="text-muted-foreground">{t('quizzes.transfer.slow')}</p>}
            </div>
          )}
          {state?.kind === 'importing' && (
            <p role="status" className="text-sm" data-testid="quiz-import-progress">
              {t('quizzes.transfer.importing', { done: state.done, total: state.total })}
            </p>
          )}
          {(state?.kind === 'refused' || state?.kind === 'failed') && (
            <Alert variant="destructive" data-testid="quiz-import-problems">
              <AlertDescription className="flex flex-col gap-1">
                <span>{state.kind === 'refused' ? t('quizzes.transfer.problems') : t('quizzes.transfer.importFailed')}</span>
                <ul className="list-disc pl-5">
                  {state.problems.map((problem, i) => <li key={i}>{problem}</li>)}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setState(null)}>
              {t('quizzes.transfer.cancel')}
            </Button>
            {state?.kind === 'checked' && (
              <Button data-testid="quiz-import-confirm" onClick={() => void start(state.document, state.check)}>
                {t('quizzes.transfer.confirm')}
              </Button>
            )}
            {(state?.kind === 'refused' || state?.kind === 'failed') && (
              <Button variant="outline" onClick={() => input.current?.click()}>{t('quizzes.transfer.choose')}</Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
