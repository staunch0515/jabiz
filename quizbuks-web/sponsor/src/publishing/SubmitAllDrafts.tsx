import { Badge, Button, ConfirmDialog, Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader,
  DialogTitle } from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { Send } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { reasonsOf } from '../content/QuizTransfer'
import { SPONSOR } from '../messages'
import { submitAllDrafts, type Submitted } from '../profile/package'
import '../profile/packageMessages'

/**
 * "Submit all drafts" (F-C4, D-Q103-5): after the sponsor confirms, submits the drafts of the Sponsor's knowledge maps
 * never in effect and then every draft publication, one after the other through the processes the pages use; a draft
 * refused is skipped and listed with the server's reasons (funds short, a map not in effect yet …). The reviews go on
 * one by one as for any.
 */
export function SubmitAllDraftsButton({ size = 'default' }: { size?: 'default' | 'sm' }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [asking, setAsking] = useState(false)
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  const [results, setResults] = useState<Submitted[] | null>(null)

  const run = async () => {
    setAsking(false)
    setProgress({ done: 0, total: 0 })
    try {
      setResults(await submitAllDrafts((done, total) => setProgress({ done, total })))
    } catch (e) {
      setResults([{ kind: 'publication', id: '', name: '', ok: false, error: e }])
    } finally {
      setProgress(null)
      await queryClient.invalidateQueries({ queryKey: ['qb'] })
    }
  }

  const ok = results?.filter((r) => r.ok).length ?? 0
  return (
    <>
      <Button variant="outline" size={size} disabled={progress !== null} aria-busy={progress !== null || undefined}
        data-testid="submit-all-drafts" onClick={() => setAsking(true)}>
        <Send aria-hidden />
        {progress ? t('submitAll.running', progress) : t('submitAll.button')}
      </Button>
      <ConfirmDialog open={asking} onOpenChange={setAsking} title={t('submitAll.title')}
        description={t('submitAll.text')} confirmLabel={t('submitAll.confirm')} cancelLabel={t('package.cancel')}
        onConfirm={() => void run()} />
      <Dialog open={results !== null} onOpenChange={(open) => !open && setResults(null)}>
        <DialogContent className="max-h-[90vh] overflow-y-auto" data-testid="submit-all-results">
          <DialogHeader>
            <DialogTitle>{t('submitAll.resultTitle')}</DialogTitle>
            <DialogDescription>
              {results && results.length === 0 ? t('submitAll.none')
                : t('submitAll.summary', { ok, skipped: (results?.length ?? 0) - ok })}
            </DialogDescription>
          </DialogHeader>
          <ul className="flex flex-col gap-2 text-sm">
            {(results ?? []).map((result, i) => (
              <li key={`${result.kind}-${result.id}-${i}`} className="flex flex-col gap-1"
                data-testid="submit-all-result" data-ok={result.ok} data-kind={result.kind}>
                <span className="flex flex-wrap items-center gap-2">
                  <Badge variant="outline">{t(`submitAll.${result.kind}`)}</Badge>
                  <span className="font-medium">{result.name}</span>
                  {result.ok && <Badge variant="secondary">{t('submitAll.ok')}</Badge>}
                </span>
                {!result.ok && reasonsOf(result.error, t).map((reason, j) => (
                  <span key={j} className="text-destructive">{t('submitAll.skipped', { reason })}</span>
                ))}
              </li>
            ))}
          </ul>
          <DialogFooter>
            <Button variant="outline" onClick={() => setResults(null)}>{t('submitAll.close')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
