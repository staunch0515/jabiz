import { Alert, AlertDescription, AlertTitle, Spinner } from '@jabiz/ui'
import { TriangleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { MailPreview } from '../../api/mail'

export interface MailPreviewPanelProps {
  preview: MailPreview | undefined
  loading: boolean
  /** Why no preview could be made (shown instead of it). */
  error?: string | null
}

/**
 * A rendered mail: its subject, its HTML in a sandboxed frame (no scripts, no same origin, no forms: the mail's own
 * HTML is never part of the page), its plain text and the problems the server's rules found in the draft.
 */
export default function MailPreviewPanel({ preview, loading, error }: MailPreviewPanelProps) {
  const { t } = useTranslation()
  const problems = preview?.problems ?? []
  return (
    <section aria-labelledby="mail-preview-title" className="flex flex-col gap-3" aria-busy={loading || undefined}>
      <div className="flex items-center gap-2">
        <h2 id="mail-preview-title" className="text-base font-semibold">{t('mail.preview.title')}</h2>
        {loading && <Spinner label={t('mail.preview.loading')} />}
      </div>
      {error && (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {problems.length > 0 && (
        <Alert variant="destructive" data-testid="mail-preview-problems">
          <TriangleAlert aria-hidden />
          <AlertTitle>{t('mail.preview.problems', { count: problems.length })}</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {problems.map((problem, index) => (
                <li key={index}>{problem.message}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      {preview && (
        <>
          <p className="text-sm">
            <span className="text-muted-foreground">{t('mail.preview.subject')}: </span>
            <span data-testid="mail-preview-subject">{preview.subject}</span>
          </p>
          <iframe
            title={t('mail.preview.frame')}
            // An empty sandbox: no scripts, no forms, no same origin, no top navigation.
            sandbox=""
            srcDoc={preview.html ?? ''}
            className="bg-background h-96 w-full rounded-md border"
            data-testid="mail-preview-frame"
          />
          <details className="text-sm">
            <summary className="cursor-pointer">{t('mail.preview.plainText')}</summary>
            <pre className="bg-muted mt-2 overflow-x-auto rounded-md p-3 text-xs whitespace-pre-wrap" data-testid="mail-preview-text">
              {preview.text}
            </pre>
          </details>
        </>
      )}
    </section>
  )
}
