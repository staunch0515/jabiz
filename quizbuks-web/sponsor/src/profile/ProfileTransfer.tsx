import { Alert, AlertDescription, Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Dialog,
  DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, Input, Label, notify } from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { Download, Upload } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { reasonsOf } from '../content/QuizTransfer'
import { download, readJsonFile } from '../content/transfer'
import { SPONSOR } from '../messages'
import { readMySponsorPage } from '../page/api'
import { readMyProducts } from '../products/api'
import { readMailServer } from '../publishing/api'
import { contentLanguageOf, readMySponsor } from '../qb/api'
import { navigate } from '../qb/router'
import { profilePath } from './paths'
import { checkProfileImport, exportProfile, importProfile, keepApplyDraft, packageFileName, type ImportPlan }
  from './transfer'

type ImportState =
  | { kind: 'checking' }
  | { kind: 'checked'; document: Record<string, unknown>; plan: ImportPlan }
  | { kind: 'importing'; done: number; total: number }
  | { kind: 'refused'; problems: string[] }
  | { kind: 'failed'; problems: string[] }

/**
 * The profile package (Q100a, docs/quizbuks/plans/Q100-profile-package.md): exports the hub's pages as one file with
 * their images and the mail server's settings (never its password), and imports such a file after showing what it
 * does. The plan is the server's; an import never changes a review (D-Q100-3).
 */
export function ProfileTransferCard() {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const passwordId = useId()
  const [exporting, setExporting] = useState(false)
  const [state, setState] = useState<ImportState | null>(null)
  const [password, setPassword] = useState('')

  const exportNow = async () => {
    setExporting(true)
    try {
      const [sponsor, page, products, mailServer] = await Promise.all([readMySponsor(), readMySponsorPage(),
        readMyProducts(), readMailServer()])
      const document = await exportProfile({ sponsor, page, products, mailServer,
        fallbackLanguage: contentLanguageOf(i18n.language) })
      const blob = new Blob([JSON.stringify(document)], { type: 'application/json' })
      download({ blob, name: packageFileName(document), size: blob.size })
      notify.success(t('profile.transfer.exported'))
    } catch (e) {
      notify.error(reasonsOf(e, t).join(' '))
    } finally {
      setExporting(false)
    }
  }

  const chosen = async (file: File) => {
    setPassword('')
    setState({ kind: 'checking' })
    let document: Record<string, unknown>
    try {
      document = await readJsonFile(file)
    } catch {
      setState({ kind: 'refused', problems: [t('profile.transfer.notJson')] })
      return
    }
    try {
      setState({ kind: 'checked', document, plan: await checkProfileImport(document) })
    } catch (e) {
      setState({ kind: 'refused', problems: reasonsOf(e, t) })
    }
  }

  const start = async (document: Record<string, unknown>, plan: ImportPlan) => {
    setState({ kind: 'importing', done: 0, total: plan.files.length })
    try {
      await importProfile(document, plan, password, (done, total) => setState({ kind: 'importing', done, total }))
      setState(null)
      notify.success(t('profile.transfer.imported'))
      await queryClient.invalidateQueries({ queryKey: ['qb'] })
      if (plan.basics === 'APPLY_FORM') {
        keepApplyDraft(document)
        navigate(profilePath('basics'))
      }
    } catch (e) {
      setState({ kind: 'failed', problems: reasonsOf(e, t) })
    }
  }

  const busy = state?.kind === 'checking' || state?.kind === 'importing'
  const plan = state?.kind === 'checked' ? state.plan : null
  const needsPassword = plan?.mailServer === 'PASSWORD'
  return (
    <Card data-testid="profile-transfer">
      <CardHeader>
        <CardTitle>{t('profile.transfer.title')}</CardTitle>
        <CardDescription>{t('profile.transfer.hint')}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-2">
        <Button variant="outline" disabled={exporting} aria-busy={exporting || undefined}
          data-testid="profile-export" onClick={() => void exportNow()}>
          <Download aria-hidden />
          {exporting ? t('profile.transfer.exporting') : t('profile.transfer.export')}
        </Button>
        <input ref={input} type="file" accept="application/json,.json" className="sr-only" tabIndex={-1} aria-hidden
          data-testid="profile-import-file" onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (file) void chosen(file)
          }} />
        <Button variant="outline" data-testid="profile-import" onClick={() => input.current?.click()}>
          <Upload aria-hidden />
          {t('profile.transfer.import')}
        </Button>
      </CardContent>
      <Dialog open={state !== null} onOpenChange={(open) => {
        if (!open && !busy) setState(null)
      }}>
        <DialogContent data-testid="profile-import-dialog">
          <DialogHeader>
            <DialogTitle>{t('profile.transfer.importTitle')}</DialogTitle>
            <DialogDescription>{t('profile.transfer.importHint')}</DialogDescription>
          </DialogHeader>
          {state?.kind === 'checking' && <p role="status" className="text-sm">{t('profile.transfer.checking')}</p>}
          {plan && (
            <div className="flex flex-col gap-3 text-sm" data-testid="profile-import-plan">
              <ul className="flex flex-col gap-1">
                <li data-testid="profile-import-basics" data-action={plan.basics}>
                  {t(`profile.transfer.basics.${plan.basics}`)}
                </li>
                <li data-testid="profile-import-page" data-action={plan.page}>
                  {t(`profile.transfer.page.${plan.page}`)}
                </li>
                <li data-testid="profile-import-mail" data-action={plan.mailServer}>
                  {t(`profile.transfer.mail.${plan.mailServer}`)}
                </li>
              </ul>
              <div data-testid="profile-import-products">
                <p className="font-medium">{t('profile.transfer.products', { added: plan.added.length,
                  changed: plan.changed.length, deleted: plan.deleted.length })}</p>
                {(['added', 'changed', 'deleted'] as const).map((group) => plan[group].length > 0 && (
                  <p key={group} className="text-muted-foreground" data-testid={`profile-import-${group}`}>
                    {t(`profile.transfer.${group}`)}: {plan[group].join('、')}
                  </p>
                ))}
              </div>
              {plan.links.length > 0 && (
                <Alert data-testid="profile-import-links">
                  <AlertDescription className="flex flex-col gap-1">
                    <span>{t('profile.transfer.links')}</span>
                    <ul className="list-disc pl-5 break-all">
                      {plan.links.map((link) => <li key={link.path}>{link.url}</li>)}
                    </ul>
                  </AlertDescription>
                </Alert>
              )}
              {needsPassword && (
                <div className="flex flex-col gap-2">
                  <Label htmlFor={passwordId}>{t('profile.transfer.password')}</Label>
                  <Input id={passwordId} type="password" autoComplete="off" value={password}
                    data-testid="profile-import-password" onChange={(event) => setPassword(event.target.value)} />
                </div>
              )}
            </div>
          )}
          {state?.kind === 'importing' && (
            <p role="status" className="text-sm" data-testid="profile-import-progress">
              {t('profile.transfer.importing', { done: state.done, total: state.total })}
            </p>
          )}
          {(state?.kind === 'refused' || state?.kind === 'failed') && (
            <Alert variant="destructive" data-testid="profile-import-problems">
              <AlertDescription className="flex flex-col gap-1">
                <span>{state.kind === 'refused' ? t('profile.transfer.problems') : t('profile.transfer.failed')}</span>
                <ul className="list-disc pl-5">
                  {state.problems.map((problem, i) => <li key={i}>{problem}</li>)}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setState(null)}>
              {t('profile.transfer.cancel')}
            </Button>
            {state?.kind === 'checked' && (
              <Button data-testid="profile-import-confirm" disabled={needsPassword && !password}
                onClick={() => void start(state.document, state.plan)}>
                {t('profile.transfer.confirm')}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}
