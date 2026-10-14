import { useId, useRef, useState, type ReactNode } from 'react'
import { InboxIcon, UploadIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn } from '../lib/utils'
import { buttonVariants } from './ui/button'
import { Spinner } from './spinner'

export interface FileUploadProps {
  /** Called with the chosen file once it passed the checks; the field shows it is busy until the promise settles. */
  onUpload: (file: File) => Promise<unknown> | void
  /**
   * The types offered and accepted, as for `<input accept>`: media types (`image/png`, `image/*`) and extensions
   * (`.csv`). Checked before uploading only to spare a round trip; the server decides by the content.
   */
  accept?: string
  /** The largest file in bytes; larger ones are refused before uploading. */
  maxSize?: number
  /** How {@link maxSize} reads in the message ("5 MB"); the bytes when absent. */
  maxSizeText?: string
  /** The button's text; "Choose a file" when absent. */
  label?: ReactNode
  /** Further text under the button (accepted types and size), tied to the input with `aria-describedby`. */
  hint?: ReactNode
  disabled?: boolean
  /**
   * `button` (default): the input drawn as a button. `dropzone`: a large area the file can also be dragged onto (an
   * import's file); clicking it, or Enter / Space on the input within it, opens the file chooser as before.
   */
  variant?: 'button' | 'dropzone'
  id?: string
  className?: string
  'aria-describedby'?: string
  /** The field has a problem the caller shows (a server's violation); the field's own refusals set it too. */
  'aria-invalid'?: boolean
  'data-testid'?: string
}

/** Whether a file is of one of the types of an `accept` list. */
export function fileMatchesAccept(file: Pick<File, 'name' | 'type'>, accept: string | undefined): boolean {
  const patterns = (accept ?? '')
    .split(',')
    .map((pattern) => pattern.trim().toLowerCase())
    .filter(Boolean)
  if (patterns.length === 0) return true
  const name = file.name.toLowerCase()
  const type = (file.type || '').toLowerCase()
  return patterns.some((pattern) =>
    pattern.startsWith('.')
      ? name.endsWith(pattern)
      : pattern.endsWith('/*')
        ? type.startsWith(pattern.slice(0, -1))
        : type === pattern,
  )
}

/**
 * Choosing and uploading a file: a real file input (keyboard, screen readers and test tools use it as usual) drawn
 * as a button. The type and size are checked before uploading; while uploading the field is busy; a refusal or
 * failure is shown as an alert (`role="alert"`) under it.
 */
export function FileUpload({
  onUpload,
  accept,
  maxSize,
  maxSizeText,
  label,
  hint,
  disabled = false,
  variant = 'button',
  id,
  className,
  'aria-describedby': describedBy,
  'aria-invalid': invalid,
  'data-testid': testId,
}: FileUploadProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const ownId = useId()
  const inputId = id ?? ownId
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)

  const choose = async (file: File | undefined) => {
    // The same file can be chosen again after a refusal.
    if (input.current) input.current.value = ''
    if (!file) return
    setError(null)
    if (!fileMatchesAccept(file, accept)) {
      setError(t('upload.wrongType', { types: accept }))
      return
    }
    if (maxSize !== undefined && file.size > maxSize) {
      setError(t('upload.tooLarge', { max: maxSizeText ?? `${maxSize} B` }))
      return
    }
    setBusy(true)
    try {
      await onUpload(file)
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('upload.failed'))
    } finally {
      setBusy(false)
    }
  }

  const describedByIds = [describedBy, hint ? `${inputId}-hint` : undefined, error ? `${inputId}-error` : undefined]
    .filter(Boolean)
    .join(' ')

  const blocked = disabled || busy
  const inputElement = (
    <input
      ref={input}
      id={inputId}
      type="file"
      accept={accept}
      disabled={blocked}
      aria-busy={busy || undefined}
      aria-invalid={error || invalid ? true : undefined}
      aria-describedby={describedByIds || undefined}
      data-testid={testId}
      className="peer sr-only"
      onChange={(event) => void choose(event.target.files?.[0])}
    />
  )
  const focusRing = 'peer-focus-visible:border-ring peer-focus-visible:ring-ring/50 peer-focus-visible:ring-[3px]'

  if (variant === 'dropzone') {
    return (
      <div data-slot="file-upload" data-variant="dropzone" className={cn('flex flex-col gap-1.5', className)}>
        {inputElement}
        <label
          htmlFor={inputId}
          data-dragging={dragging || undefined}
          className={cn(
            'border-input bg-background hover:bg-muted/50 flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-6 text-center text-sm transition-colors',
            'data-[dragging]:border-primary data-[dragging]:bg-muted',
            focusRing,
            'peer-disabled:pointer-events-none peer-disabled:opacity-50',
          )}
          onDragOver={(event) => {
            if (blocked) return
            // Without this the browser opens the dropped file itself.
            event.preventDefault()
            setDragging(true)
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            if (blocked) return
            event.preventDefault()
            setDragging(false)
            void choose(event.dataTransfer.files?.[0])
          }}
        >
          {busy ? <Spinner label={t('upload.uploading')} showLabel /> : <InboxIcon aria-hidden className="text-muted-foreground size-8" />}
          <span className="font-medium">{label ?? t('upload.dropzone')}</span>
          {/* Part of the input's description, not of its name (a description may point at hidden text). */}
          {hint && (
            <span id={`${inputId}-hint`} aria-hidden className="text-muted-foreground text-xs">
              {hint}
            </span>
          )}
        </label>
        {error && (
          <p id={`${inputId}-error`} role="alert" className="text-destructive text-sm">
            {error}
          </p>
        )}
      </div>
    )
  }

  return (
    <div data-slot="file-upload" className={cn('flex flex-col gap-1.5', className)}>
      <div className="flex items-center gap-2">
        {inputElement}
        <label
          htmlFor={inputId}
          className={cn(
            buttonVariants({ variant: 'outline' }),
            'cursor-pointer',
            focusRing,
            'peer-disabled:pointer-events-none peer-disabled:opacity-50',
          )}
        >
          <UploadIcon aria-hidden />
          {label ?? t('upload.choose')}
        </label>
        {busy && <Spinner label={t('upload.uploading')} showLabel />}
      </div>
      {hint && (
        <p id={`${inputId}-hint`} className="text-muted-foreground text-xs">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${inputId}-error`} role="alert" className="text-destructive text-sm">
          {error}
        </p>
      )}
    </div>
  )
}
