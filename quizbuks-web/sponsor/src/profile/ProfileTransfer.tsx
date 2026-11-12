import { Alert, AlertDescription, Button, Checkbox, Dialog,
  DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, Input, Label, notify } from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { Copy } from 'lucide-react'
import { useEffect, useId, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { useTranslation } from 'react-i18next'
import { reasonsOf } from '../content/QuizTransfer'
import { download, readJsonFile } from '../content/transfer'
import { SPONSOR } from '../messages'
import { readMySponsorPage } from '../page/api'
import { readMyProducts } from '../products/api'
import { readMailServer } from '../publishing/api'
import { contentLanguageOf, readMySponsor } from '../qb/api'
import { navigate } from '../qb/router'
import { AiPromptDialog } from './AiPromptDialog'
import { draftDocument, type SiteDraft } from './site/api'
import { SiteDraftNotes } from './site/SiteDraftNotes'
import { SiteProfileDialog } from './site/SiteProfileDialog'
import { fixPrompt } from './aiPrompt'
import { copyText } from './copyText'
import { profilePath } from './paths'
import { checkProfileImport, downloadLink, exportProfile, importProfile, keepApplyDraft, packageFileName,
  uploadForLink, type ImportPlan, type LinkNeed } from './transfer'

/** One linked image (F-C6): not asked yet, being downloaded, a file of the sponsor's, or refused with the reasons. */
type LinkState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'done'; fileId: string; own: boolean }
  | { kind: 'failed'; reasons: string[] }

type ImportState =
  | { kind: 'checking' }
  | { kind: 'checked'; document: Record<string, unknown>; plan: ImportPlan }
  | { kind: 'importing'; done: number; total: number }
  | { kind: 'refused'; problems: string[] }
  | { kind: 'failed'; problems: string[] }

/** What the menu in the overview's head does with the profile package (F-F3d). */
export interface ProfileTransferControls {
  exportNow: () => void
  /** Opens the file choice: browsers allow it only within the click itself, so this clicks the input. */
  pickFile: () => void
  openPrompt: () => void
  /** F-C7: the dialog that drafts the profile from the sponsor's website. */
  openSite: () => void
}

/**
 * The profile package (Q100a, docs/quizbuks/plans/Q100-profile-package.md): exports the hub's pages as one file with
 * their images and the mail server's settings (never its password), and imports such a file after showing what it
 * does. The plan is the server's; an import never changes a review (D-Q100-3).
 */
export function ProfileTransfer({ controls, onExporting }: {
  /** The page head's "Import and export" menu (F-F3d) starts each of the three through this; the dialogs stay here. */
  controls?: Ref<ProfileTransferControls>
  /** Whether an export is running, for the menu's item. */
  onExporting?: (exporting: boolean) => void
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const passwordId = useId()
  const [exporting, setExporting] = useState(false)
  const [state, setState] = useState<ImportState | null>(null)
  const [password, setPassword] = useState('')
  const [prompting, setPrompting] = useState(false)
  // F-C7: the website draft dialog, and the draft whose review the confirmation shows (with the link name in force).
  const [siteOpen, setSiteOpen] = useState(false)
  const [site, setSite] = useState<{ draft: SiteDraft; handle: string } | null>(null)
  // D-Q100-13: the sponsor confirms that deleted products leave the quizzes they are shown on.
  const [leaving, setLeaving] = useState(false)
  const leavingId = useId()
  // F-C6: what became of each linked image (by its path), and whether the sponsor lets the failed ones stay as they are.
  const [linkStates, setLinkStates] = useState<Record<string, LinkState>>({})
  const [keepImages, setKeepImages] = useState(false)
  const keepId = useId()
  const replacement = useRef<HTMLInputElement>(null)
  const [replacing, setReplacing] = useState<LinkNeed | null>(null)
  const setLink = (path: string, next: LinkState) => setLinkStates((all) => ({ ...all, [path]: next }))
  useEffect(() => onExporting?.(exporting), [exporting, onExporting])

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

  useImperativeHandle(controls, () => ({
    exportNow: () => void exportNow(),
    pickFile: () => input.current?.click(),
    openPrompt: () => setPrompting(true),
    openSite: () => setSiteOpen(true),
  }))

  // F-C5: the problems as they are, for the AI tool that wrote the file (alone, or in the fixing prompt).
  const copyProblems = async (problems: string[], withPrompt: boolean) => {
    const done = await copyText(withPrompt ? fixPrompt(problems) : problems.join('\n'))
    if (done) notify.success(t('profile.transfer.problemsCopied'))
    else notify.error(t('profile.transfer.prompt.copyFailed'))
  }

  const checkDocument = async (document: Record<string, unknown>) => {
    try {
      setState({ kind: 'checked', document, plan: await checkProfileImport(document) })
    } catch (e) {
      setState({ kind: 'refused', problems: reasonsOf(e, t) })
    }
  }

  const begin = () => {
    setPassword('')
    setLeaving(false)
    setLinkStates({})
    setKeepImages(false)
    setState({ kind: 'checking' })
  }

  const chosen = async (file: File) => {
    begin()
    setSite(null)
    let document: Record<string, unknown>
    try {
      document = await readJsonFile(file)
    } catch {
      setState({ kind: 'refused', problems: [t('profile.transfer.notJson')] })
      return
    }
    await checkDocument(document)
  }

  // F-C7: a draft from the sponsor's website goes through the same check and confirmation as a file does; the account's
  // contact name goes in (the server never takes one from the website).
  const reviewDraft = async (draft: SiteDraft, handle: string = draft.handle?.handle ?? '') => {
    begin()
    setSiteOpen(false)
    setSite({ draft, handle })
    try {
      const sponsor = await readMySponsor()
      await checkDocument(draftDocument(draft, sponsor?.contactName ?? '', handle || undefined))
    } catch (e) {
      setState({ kind: 'refused', problems: reasonsOf(e, t) })
    }
  }

  // F-C6: the server downloads every linked image that has no file yet, one after the other (the platform lets a
  // sponsor have two downloads at once); what it refuses is shown with its reasons and can be tried again.
  const downloadOne = async (link: LinkNeed): Promise<LinkState> => {
    setLink(link.path, { kind: 'loading' })
    let result: LinkState
    try {
      result = { kind: 'done', fileId: await downloadLink(link), own: false }
    } catch (e) {
      result = { kind: 'failed', reasons: reasonsOf(e, t) }
    }
    setLink(link.path, result)
    return result
  }

  const replaceWith = async (link: LinkNeed, file: File) => {
    setLink(link.path, { kind: 'loading' })
    try {
      setLink(link.path, { kind: 'done', fileId: await uploadForLink(link, file, file.name), own: true })
    } catch (e) {
      setLink(link.path, { kind: 'failed', reasons: reasonsOf(e, t) })
    }
  }

  const start = async (document: Record<string, unknown>, plan: ImportPlan) => {
    const states = { ...linkStates }
    for (const link of plan.links) {
      // A refused one is not asked again here: the sponsor retries it, replaces it or lets it stay.
      if (!states[link.path] || states[link.path].kind === 'idle') states[link.path] = await downloadOne(link)
    }
    // A refused image waits for the sponsor's choice: try again, upload another, or let the image stay as it is.
    if (plan.links.some((link) => states[link.path]?.kind !== 'done') && !keepImages) return
    const linked: Record<string, string> = {}
    for (const link of plan.links) {
      const state = states[link.path]
      if (state?.kind === 'done') linked[link.path] = state.fileId
    }
    setState({ kind: 'importing', done: 0, total: plan.files.length })
    try {
      await importProfile(document, plan, password, (done, total) => setState({ kind: 'importing', done, total }),
        linked, site?.draft.aiJobId)
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
  // F-C7: a drafted link name that is another Sponsor's is not imported; the sponsor picks a free one first.
  const handleTaken = site?.draft.handle?.taken === true && site.handle === site.draft.handle.handle
  const linked = plan?.linked ?? []
  const refused = (plan?.links ?? []).filter((link) => linkStates[link.path]?.kind === 'failed')
  const downloading = (plan?.links ?? []).some((link) => linkStates[link.path]?.kind === 'loading')
  return (
    <>
      <input ref={input} type="file" accept="application/json,.json" className="sr-only" tabIndex={-1} aria-hidden
        data-testid="profile-import-file" onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) void chosen(file)
        }} />
      <AiPromptDialog open={prompting} onClose={() => setPrompting(false)} />
      <SiteProfileDialog open={siteOpen} onClose={() => setSiteOpen(false)} onReview={(draft) => void reviewDraft(draft)}
        onPrompt={() => {
          setSiteOpen(false)
          setPrompting(true)
        }} />
      <Dialog open={state !== null} onOpenChange={(open) => {
        if (!open && !busy) setState(null)
      }}>
        {/* The review of a draft from a website is long (fields, notes, articles, the quiz): the dialog scrolls, so that its buttons are always reachable. */}
        <DialogContent className="sm:max-w-2xl" data-testid="profile-import-dialog">
          <DialogHeader>
            <DialogTitle>{t('profile.transfer.importTitle')}</DialogTitle>
            <DialogDescription>{t('profile.transfer.importHint')}</DialogDescription>
          </DialogHeader>
          {state?.kind === 'checking' && <p role="status" className="text-sm">{t('profile.transfer.checking')}</p>}
          {site && state && state.kind !== 'checking' && (
            <SiteDraftNotes draft={site.draft} handle={site.handle}
              onHandle={(handle) => void reviewDraft(site.draft, handle)} />
          )}
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
              {linked.length > 0 && (
                <Alert variant="destructive" data-testid="profile-import-linked">
                  <AlertDescription className="flex flex-col gap-2">
                    <span>{t('profile.transfer.linked')}</span>
                    <ul className="list-disc pl-5">
                      {linked.map((item) => (
                        <li key={item.product}>{t('profile.transfer.linkedItem', { product: item.product,
                          quizzes: item.quizzes.join('、') })}</li>
                      ))}
                    </ul>
                    <span className="flex items-center gap-2">
                      <Checkbox id={leavingId} checked={leaving} data-testid="profile-import-leaving"
                        onCheckedChange={(checked) => setLeaving(checked === true)} />
                      <Label htmlFor={leavingId}>{t('profile.transfer.linkedConfirm')}</Label>
                    </span>
                  </AlertDescription>
                </Alert>
              )}
              {plan.links.length > 0 && (
                <Alert data-testid="profile-import-links">
                  <AlertDescription className="flex flex-col gap-1">
                    <span>{t('profile.transfer.links')}</span>
                    <ul className="flex flex-col gap-2">
                      {plan.links.map((link, i) => {
                        const state = linkStates[link.path] ?? { kind: 'idle' as const }
                        return (
                          <li key={link.path} className="flex flex-col gap-1" data-testid={`profile-import-link-${i}`}
                            data-state={state.kind}>
                            <span className="break-all">{link.url}</span>
                            <span className="text-muted-foreground" role={state.kind === 'failed' ? 'alert' : 'status'}>
                              {t(`profile.transfer.linkState.${state.kind === 'done' && state.own ? 'own' : state.kind}`)}
                            </span>
                            {state.kind === 'failed' && (
                              <>
                                <ul className="list-disc pl-5" data-testid={`profile-import-link-${i}-reasons`}>
                                  {state.reasons.map((reason, r) => <li key={r}>{reason}</li>)}
                                </ul>
                                <span className="flex flex-wrap gap-2">
                                  <Button type="button" variant="outline" size="sm" disabled={downloading}
                                    data-testid={`profile-import-link-${i}-retry`} onClick={() => void downloadOne(link)}>
                                    {t('profile.transfer.linkRetry')}
                                  </Button>
                                  <Button type="button" variant="outline" size="sm" disabled={downloading}
                                    data-testid={`profile-import-link-${i}-upload`} onClick={() => {
                                      setReplacing(link)
                                      replacement.current?.click()
                                    }}>
                                    {t('profile.transfer.linkUpload')}
                                  </Button>
                                </span>
                              </>
                            )}
                          </li>
                        )
                      })}
                    </ul>
                    {refused.length > 0 && (
                      <span className="flex items-center gap-2">
                        <Checkbox id={keepId} checked={keepImages} data-testid="profile-import-keep-images"
                          onCheckedChange={(checked) => setKeepImages(checked === true)} />
                        <Label htmlFor={keepId}>{t('profile.transfer.linkKeep')}</Label>
                      </span>
                    )}
                    <input ref={replacement} type="file" accept="image/jpeg,image/png" className="sr-only" tabIndex={-1}
                      aria-hidden data-testid="profile-import-link-file" onChange={(event) => {
                        const file = event.target.files?.[0]
                        event.target.value = ''
                        if (file && replacing) void replaceWith(replacing, file)
                      }} />
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
                <span className="flex flex-wrap gap-2">
                  <Button type="button" variant="outline" size="sm" data-testid="profile-problems-copy"
                    onClick={() => void copyProblems(state.problems, false)}>
                    <Copy aria-hidden />{t('profile.transfer.copyProblems')}
                  </Button>
                  <Button type="button" variant="outline" size="sm" data-testid="profile-problems-copy-fix"
                    onClick={() => void copyProblems(state.problems, true)}>
                    <Copy aria-hidden />{t('profile.transfer.copyFix')}
                  </Button>
                </span>
              </AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setState(null)}>
              {t('profile.transfer.cancel')}
            </Button>
            {state?.kind === 'checked' && (
              <Button data-testid="profile-import-confirm"
                disabled={(needsPassword && !password) || (linked.length > 0 && !leaving) || downloading
                  || (refused.length > 0 && !keepImages) || handleTaken}
                onClick={() => void start(state.document, state.plan)}>
                {t('profile.transfer.confirm')}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
