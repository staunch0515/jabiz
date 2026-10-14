import { ApiError, uploadFile } from '@jabiz/client'
import { Button, FilePreview, FileUpload, Label, MarkdownView, notify, Textarea } from '@jabiz/ui'
import { X } from 'lucide-react'
import { createContext, useContext, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { isStale, type EditOutput } from './api'
import { CONTENT_FILE_POLICY } from './ids'
import { ContentImage } from './ContentImage'
import { DOCUMENT_FIELD, UPLOADS } from './limits'

/**
 * What every part of the quiz editor needs: which quiz, the revision it last saw (sent as `baseRevision`, so the
 * server refuses an edit of a quiz that changed meanwhile, `QB_QUIZ_CHANGED`), and what to do after an edit.
 */
export interface Editing {
  quizId: string
  /** The revision of the quiz as the page last saw it. */
  revision: () => number
  /** After a successful edit: remembers the new revision and reads the quiz again. */
  edited: (output: Pick<EditOutput, 'revision'>) => Promise<void>
  /** After a failed edit: an out-of-date page asks to reload (the caller still shows other problems). */
  failed: (error: unknown) => void
  /** The page is out of date: edits are held until it is reloaded. */
  stale: boolean
}

const EditingContext = createContext<Editing | null>(null)

export const EditingProvider = EditingContext.Provider

export function useEditing(): Editing {
  const editing = useContext(EditingContext)
  if (!editing) throw new Error('useEditing outside the quiz editor')
  return editing
}

/**
 * A form's problem that is not about one of its fields; nothing for an out-of-date page, which the editor's banner
 * explains once.
 */
export function formProblem(error: unknown, fields: readonly string[]): string[] {
  if (error === undefined || error === null || isStale(error)) return []
  if (!(error instanceof ApiError) || error.violations.length === 0) return [problemText(error)]
  return error.violations.filter((v) => !v.field || !fields.includes(v.field)).map((v) => v.message)
}

/** The problems of a form, one per line, for its alert. */
export function ProblemList({ problems, testId }: { problems: string[]; testId?: string }) {
  if (problems.length === 0) return null
  return (
    <div role="alert" className="border-destructive/50 text-destructive rounded-lg border px-4 py-3 text-sm"
      data-testid={testId}>
      {problems.length === 1
        ? <p>{problems[0]}</p>
        : <ul className="list-disc space-y-1 pl-4">{problems.map((p, i) => <li key={i}>{p}</li>)}</ul>}
    </div>
  )
}

/** Where to send an upload of each kind of file (the policies of `content.ContentConfig`). */
const POLICY = { image: CONTENT_FILE_POLICY.image, pdf: CONTENT_FILE_POLICY.pdf, audio: CONTENT_FILE_POLICY.audio }

/**
 * A file of the quiz: shown (an image as a thumbnail that opens larger, a PDF or audio file as a download), replaced
 * by uploading another under its policy, or removed. The server checks the type by the content and re-encodes
 * images (removing location data); the page checks type and size first only to spare the round trip.
 */
export function FileField({ kind, label, uploadLabel, value, onChange, error, testId, disabled }: {
  kind: 'image' | 'pdf' | 'audio'
  /** What the file is ("Cover image"). */
  label: string
  /** The upload button's text, naming what it uploads. */
  uploadLabel: string
  value: string | null
  onChange: (fileId: string | null) => void
  /** The server's problem with this field. */
  error?: string
  testId: string
  disabled?: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const upload = UPLOADS[kind]
  const hint = t(`editor.fileHint.${kind}`, { size: upload.maxSizeText })
  return (
    <div role="group" aria-labelledby={`${id}-label`} className="flex flex-col gap-2" data-testid={testId}>
      <span id={`${id}-label`} className="text-sm font-medium">{label}</span>
      {value && (
        <div className="flex items-center gap-2" data-testid={`${testId}-current`}>
          {kind === 'image' ? <ContentImage fileId={value} width={160} />
            : <FilePreview fileId={value} field={DOCUMENT_FIELD} width={160} />}
          <Button type="button" variant="outline" size="sm" disabled={disabled} data-testid={`${testId}-remove`}
            aria-label={t('editor.removeFile', { what: label })} onClick={() => onChange(null)}>
            <X aria-hidden />
            {t('editor.remove')}
          </Button>
        </div>
      )}
      <FileUpload accept={upload.accept} maxSize={upload.maxSize} maxSizeText={upload.maxSizeText}
        label={uploadLabel} hint={hint} disabled={disabled} aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined} data-testid={`${testId}-upload`}
        onUpload={async (file) => {
          const uploaded = await uploadFile(POLICY[kind], file, file.name)
          onChange(uploaded.fileId ?? null)
        }} />
      {error && <p id={`${id}-error`} className="text-destructive text-sm">{error}</p>}
    </div>
  )
}

/**
 * A Markdown text (an introduction, an article) with a preview as takers will see it: rendered by `MarkdownView`,
 * which drops raw HTML and unsafe links.
 */
export function MarkdownField({ label, value, onChange, maxLength, rows = 8, error, testId }: {
  label: string
  value: string
  onChange: (value: string) => void
  maxLength: number
  rows?: number
  error?: string
  testId: string
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [preview, setPreview] = useState(false)
  const describedBy = [`${id}-hint`, error ? `${id}-error` : undefined].filter(Boolean).join(' ')
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Label htmlFor={id}>{label}</Label>
        <Button type="button" variant="ghost" size="sm" aria-expanded={preview} aria-controls={`${id}-preview`}
          data-testid={`${testId}-preview-toggle`} onClick={() => setPreview((p) => !p)}>
          {preview ? t('editor.hidePreview') : t('editor.showPreview')}
        </Button>
      </div>
      <Textarea id={id} value={value} rows={rows} maxLength={maxLength} aria-describedby={describedBy}
        aria-invalid={error ? true : undefined} data-testid={testId} onChange={(event) => onChange(event.target.value)} />
      <p id={`${id}-hint`} className="text-muted-foreground text-sm">
        {t('editor.markdownHint', { max: maxLength.toLocaleString() })}
      </p>
      {error && <p id={`${id}-error`} className="text-destructive text-sm">{error}</p>}
      <div id={`${id}-preview`} hidden={!preview} className="rounded-md border p-4"
        data-testid={`${testId}-preview`}>
        <p className="text-muted-foreground mb-2 text-xs font-medium uppercase">{t('editor.preview')}</p>
        {preview && (value.trim() ? <MarkdownView text={value} />
          : <p className="text-muted-foreground text-sm">{t('editor.previewEmpty')}</p>)}
      </div>
    </div>
  )
}

/**
 * After a confirmed deletion failed: an out-of-date page closes the confirmation (the banner asks to reload); any
 * other problem is told and keeps it open.
 */
export function failedRemoval(editing: Editing, error: unknown): void {
  editing.failed(error)
  if (isStale(error)) return
  notify.error(formProblem(error, []).join(' '))
  throw error
}

/** Text to send: blank is none. */
export const textOrNull = (value: string): string | null => (value.trim() === '' ? null : value)

/** A whole number typed into a field, or null when blank or not a number. */
export function wholeOrNull(value: string): number | null {
  if (value.trim() === '') return null
  const n = Number(value)
  return Number.isInteger(n) ? n : null
}

let keys = 0
/** A key of a row being edited that has no server id yet. */
export const newKey = () => `new-${++keys}`
