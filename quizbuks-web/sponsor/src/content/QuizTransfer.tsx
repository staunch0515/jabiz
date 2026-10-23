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
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { navigate } from '../qb/router'
import { quizPath, QUIZZES_QUERY } from './paths'
import {
  checkImport,
  download,
  exportFile,
  exportQuiz,
  importQuiz,
  LARGE_EXPORT_BYTES,
  MissingFileData,
  readJsonFile,
  type ExportFile,
  type ImportCheck,
} from './transfer'

/**
 * A quiz's "Export" (Q16; docs/quizbuks/plans/Q16-quiz-transfer.md): the draft and every file it uses into one JSON
 * file the browser downloads. Fetching the files shows how far it got; a file over 100 MB is downloaded after asking.
 */
export function ExportQuizButton({ quizId, title }: { quizId: string; title: string }) {
  const { t } = useTranslation(SPONSOR)
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  const [large, setLarge] = useState<ExportFile | null>(null)

  const run = async () => {
    setProgress({ done: 0, total: 0 })
    try {
      const document = await exportQuiz(quizId, (done, total) => setProgress({ done, total }))
      if (!document) throw new Error('not found')
      // Written once: the size asked about is the file downloaded.
      const file = exportFile(document)
      if (file.size > LARGE_EXPORT_BYTES) setLarge(file)
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

/** The lines a refusal lists: each violation's text, else the general one. */
function problemsOf(error: unknown, missing: (key: string) => string): string[] {
  if (error instanceof MissingFileData) return [missing(error.key)]
  if (error instanceof ApiError && error.violations.length > 0) {
    return error.violations.map((v) => (v.field ? `${v.field}: ${v.message}` : v.message))
  }
  return [problemText(error)]
}

/**
 * "Import" (Q16): a quiz file becomes a new draft. The server first says which version of the format reads the file,
 * what it makes and which files it needs, or every reason it cannot be imported; the files are then uploaded through
 * the content's policies and the draft made in one step, and the editor opens on it.
 */
export function ImportQuizButton() {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [state, setState] = useState<ImportState | null>(null)
  const missing = (key: string) => t('quizzes.transfer.missingData', { key })

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
      setState({ kind: 'refused', problems: problemsOf(e, missing) })
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
      setState({ kind: 'failed', problems: problemsOf(e, missing) })
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
        <DialogContent data-testid="quiz-import-dialog">
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
              <p className="text-muted-foreground" data-testid="quiz-import-version">
                {t('quizzes.transfer.version', { version: state.check.formatVersion,
                  current: state.check.currentVersion })}
                {state.check.readers.length > 1 && ` ${t('quizzes.transfer.upgraded', {
                  readers: state.check.readers.join(' → ') })}`}
              </p>
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
