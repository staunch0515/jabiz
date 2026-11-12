import { formatBytes } from '@jabiz/client'
import { Alert, AlertDescription, Button, ConfirmDialog,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, Input, Label, notify }
  from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { TFunction } from 'i18next'
import { useEffect, useId, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { useTranslation } from 'react-i18next'
import { readBankLimits, searchQuizzes } from '../content/api'
import { reasonsOf } from '../content/QuizTransfer'
import { checkImport, download, readJsonFile, type ImportCheck } from '../content/transfer'
import { SPONSOR } from '../messages'
import { SubmitAllDraftsButton } from '../publishing/SubmitAllDrafts'
import { contentLanguageOf } from '../qb/api'
import { Link } from '../qb/router'
import { readSharedVideo } from '../sharedvideos/api'
import {
  abandonImport,
  exportPackage,
  packageFile,
  packageHash,
  mergeChecks,
  planImport,
  readAccountState,
  readOpenImport,
  readOwnTreeNodes,
  readOwnTrees,
  readPackage,
  runImport,
  PackageStepError,
  type ImportChecks,
  type ImportPlanned,
  type OwnNode,
  type PlanContext,
  type PlannedStep,
  type SponsorPackageDocument,
} from './package'
import './packageMessages'
import { profilePath } from './paths'
import { checkProfileImport, keepApplyDraft, type ImportPlan } from './transfer'

/** A package read, checked and planned: what the import needs and what the check page shows. */
interface Checked {
  document: SponsorPackageDocument
  hash: string
  context: PlanContext
  plan: ImportPlanned
  profilePlan: ImportPlan | null
  quizChecks: Record<string, ImportCheck>
  sharedVideos: ReadonlySet<string>
  /** The server's problems: nothing is imported while there is one. */
  problems: string[]
}

type State =
  | { kind: 'checking' }
  | { kind: 'refused'; problems: string[] }
  | { kind: 'checked'; checked: Checked }
  | { kind: 'importing'; checked: Checked; label: string; index: number; total: number }
  | { kind: 'failed'; checked: Checked; label: string; problems: string[]; emptied: boolean }
  | { kind: 'done'; checked: Checked; applyForm: boolean }

const OPEN_IMPORT_QUERY = ['qb', 'package-import', 'open'] as const

/** What a step does, in words. */
export function stepLabel(step: PlannedStep, t: TFunction): string {
  const label = (text: string) => text || t('package.current')
  switch (step.kind) {
    case 'profile':
      return t('package.stepProfile')
    case 'tree':
      return t('package.stepTree', { name: Object.values(step.tree.name).find(Boolean) ?? step.tree.key })
    case 'content':
      return t('package.stepContent', { title: step.title, label: label(step.label) })
    case 'clear':
      return t('package.stepClear', { title: step.title, label: label(step.label), clear: step.clear,
        clears: step.clears })
    case 'batch':
      return t('package.stepBatch', { title: step.title, label: label(step.label), batch: step.batch,
        batches: step.batches })
    case 'version':
      return t('package.stepVersion', { title: step.title, label: step.label })
    case 'publication':
      return t('package.stepPublication', { name: step.name })
  }
}

/** Megabytes as the page says them (a request's limit is bytes). */
const megabytes = (bytes: number) => (bytes / 1_048_576).toLocaleString(undefined, { maximumFractionDigits: 1 })

/** Every title of the Sponsor's quizzes, page by page. */
async function quizTitles(): Promise<string[]> {
  const titles: string[] = []
  for (let offset = 0; ; offset += 100) {
    const page = await searchQuizzes({ offset, limit: 100 })
    titles.push(...page.items.map((row) => row.title))
    if (page.items.length < 100) return titles
  }
}

/** Reads, checks and plans a package: every problem at once (the server's checks of each part) and what it makes. */
async function checkPackage(document: SponsorPackageDocument, hash: string, t: TFunction): Promise<Checked> {
  const [state, limits, titles] = await Promise.all([readAccountState(), readBankLimits(), quizTitles()])
  const ownNodes: OwnNode[] = []
  if (state.submittedTree) {
    for (const tree of await readOwnTrees()) {
      if (!tree.currentVersionId) continue
      for (const node of await readOwnTreeNodes(tree.currentVersionId)) {
        ownNodes.push({ treeId: tree.treeId, nodeId: node.key, name: node.name })
      }
    }
  }
  const sharedVideos = new Set<string>()
  for (const id of new Set(document.publications.map((p) => p.sharedVideo).filter((v): v is string => !!v))) {
    const video = await readSharedVideo(id).catch(() => null)
    if (video?.status === 'APPROVED') sharedVideos.add(id)
  }
  const context: PlanContext = { mode: state.submittedPublication ? 'PROFILE' : 'FULL',
    batchSize: limits.importBatch || 200, batchBytes: limits.importBatchBytes || 0, maxQuestions: limits.maxQuestions || 1000, state, quizTitles: titles,
    ownNodes, sharedVideos }
  const plan = planImport(document, context)
  const problems: string[] = []
  let profilePlan: ImportPlan | null = null
  if (document.profile) {
    try {
      profilePlan = await checkProfileImport(document.profile as unknown as Record<string, unknown>)
    } catch (e) {
      reasonsOf(e, t).forEach((reason) => problems.push(t('package.profileProblem', { reason })))
    }
  }
  const quizChecks: Record<string, ImportCheck> = {}
  // With a byte limit a content is checked as the pieces it is imported in (the whole file may be more than a
  // request takes); without one, as the one file, as it always was.
  const pieces = new Map<string, Record<string, unknown>[]>()
  if (plan.batchBytes > 0) {
    for (const step of plan.steps) {
      if (step.kind === 'content' || step.kind === 'batch') pieces.set(step.check, [...(pieces.get(step.check) ?? []), step.document])
    }
  }
  if (context.mode === 'FULL') {
    for (const quiz of document.quizzes) {
      const files: [string, string, Record<string, unknown>][] = quiz.versions.map((v) => [`${quiz.key}:${v.key}`,
        v.label, v.file])
      if (quiz.current) files.push([`${quiz.key}:current`, t('package.current'), quiz.current])
      for (const [id, label, file] of files) {
        const title = typeof (file.quiz as { title?: unknown } | undefined)?.title === 'string'
          ? String((file.quiz as { title: string }).title) : quiz.key
        try {
          const checks: ImportCheck[] = []
          for (const piece of pieces.get(id) ?? [file]) checks.push(await checkImport(piece))
          quizChecks[id] = mergeChecks(checks)
        } catch (e) {
          reasonsOf(e, t).forEach((reason) => problems.push(t('package.quizProblem', { title, label, reason })))
        }
      }
    }
  }
  return { document, hash, context, plan, profilePlan, quizChecks, sharedVideos, problems }
}

/** What the menu in the overview's head (and the notice of an import left open) does with the package (F-F3d). */
export interface SponsorPackageControls {
  exportNow: () => void
  /** Opens the file choice: browsers allow it only within the click itself, so this clicks the input. */
  pickFile: () => void
  askAbandon: (importId: string) => void
}

/**
 * The sponsor package on the overview (F-C4, docs/quizbuks/plans/impl/F-C4.md): exports the profile, quizzes,
 * publications not ended and knowledge maps as one file; imports such a file after a check page listing every problem
 * and what it makes, step by step with its progress, through the server's import record. An import stopped half-way
 * goes on with the same file (no step is done twice) or is given up (its drafts removed).
 */
export function SponsorPackage({ controls, onExporting }: {
  /** The page head's "Import and export" menu (F-F3d) starts each through this; the dialogs stay here. */
  controls?: Ref<SponsorPackageControls>
  /** What an export in progress is on (null when none), for the menu's item. */
  onExporting?: (what: string | null) => void
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const passwordId = useId()
  const [exporting, setExporting] = useState<string | null>(null)
  const [large, setLarge] = useState<{ blob: Blob; name: string; size: number } | null>(null)
  const [state, setState] = useState<State | null>(null)
  const [password, setPassword] = useState('')
  const [abandoning, setAbandoning] = useState<string | null>(null)
  useEffect(() => onExporting?.(exporting), [exporting, onExporting])

  const exportNow = async () => {
    setExporting(t('package.exportingProfile'))
    try {
      const [document, account] = await Promise.all([
        exportPackage(contentLanguageOf(i18n.language), (what, done, total) => setExporting(
          what === 'publications' ? t('package.exportingPublications', { done: done + 1, total })
            : what === 'quizzes' ? t('package.exportingQuizzes', { done: done + 1, total })
              : what === 'trees' ? t('package.exportingTrees') : t('package.exportingProfile'))),
        readAccountState()])
      const file = packageFile(document)
      if (file.size > account.confirmBytes) setLarge(file)
      else {
        download(file)
        notify.success(t('package.exported'))
      }
    } catch (e) {
      notify.error(t('package.exportFailed', { reason: reasonsOf(e, t).join(' ') }))
    } finally {
      setExporting(null)
    }
  }

  const chosen = async (file: File) => {
    setPassword('')
    setState({ kind: 'checking' })
    let raw: Record<string, unknown>
    try {
      raw = await readJsonFile(file)
    } catch {
      setState({ kind: 'refused', problems: [t('package.notJson')] })
      return
    }
    const read = readPackage(raw)
    if ('problem' in read) {
      setState({ kind: 'refused', problems: [read.problem === 'shape'
        ? t('package.shape', { places: (read.places ?? []).join(', ') }) : t(`package.${read.problem}`)] })
      return
    }
    try {
      setState({ kind: 'checked', checked: await checkPackage(read.document, await packageHash(file), t) })
    } catch (e) {
      setState({ kind: 'refused', problems: reasonsOf(e, t) })
    }
  }

  const start = async (checked: Checked) => {
    const checks: ImportChecks = { profile: checked.profilePlan, quizzes: checked.quizChecks, mailPassword: password,
      sharedVideos: checked.sharedVideos }
    let last: PlannedStep | null = null
    try {
      const run = await runImport({ document: checked.document, hash: checked.hash, plan: checked.plan, checks,
        replan: (batchSize, batchBytes) => planImport(checked.document, { ...checked.context, batchSize, batchBytes }),
        onProgress: (step, index, total) => {
          last = step
          setState({ kind: 'importing', checked, label: stepLabel(step, t), index, total })
        } })
      const applyForm = run.results.profile?.basics === 'APPLY_FORM'
      if (applyForm && checked.document.profile) {
        keepApplyDraft(checked.document.profile as unknown as Record<string, unknown>)
      }
      setState({ kind: 'done', checked, applyForm })
      notify.success(t('package.done'))
    } catch (e) {
      const step = e instanceof PackageStepError ? e.step : last
      // A step that clears the content a draft had, or the one that brings the next, stopped: the draft is empty (or
      // half empty) until the import goes on.
      const emptied = !!step && (step.kind === 'clear' || (step.kind === 'content' && step.clears > 0))
      setState({ kind: 'failed', checked, label: step ? stepLabel(step, t) : t('package.title'),
        problems: reasonsOf(e instanceof PackageStepError ? e.cause : e, t), emptied })
    } finally {
      await queryClient.invalidateQueries({ queryKey: ['qb'] })
    }
  }

  const abandon = async (importId: string) => {
    setAbandoning(null)
    try {
      const out = await abandonImport(importId)
      notify.success(t('package.abandoned', { publications: out.deletedPublications.length,
        quizzes: out.removedQuizzes.length, trees: out.retiredTrees.length })
        + (out.keptQuizzes.length > 0 ? ` ${t('package.keptQuizzes', { count: out.keptQuizzes.length })}` : ''))
      setState(null)
    } catch (e) {
      notify.error(reasonsOf(e, t).join(' '))
    } finally {
      await queryClient.invalidateQueries({ queryKey: ['qb'] })
    }
  }

  useImperativeHandle(controls, () => ({
    exportNow: () => void exportNow(),
    pickFile: () => input.current?.click(),
    askAbandon: setAbandoning,
  }))

  const busy = state?.kind === 'checking' || state?.kind === 'importing'
  const checked = state && 'checked' in state ? state.checked : null
  const plan = checked?.plan
  const profileOnly = plan?.mode === 'PROFILE'
  const needsPassword = checked?.profilePlan?.mailServer === 'PASSWORD'
  const notices = plan ? ([
    ['sameTitles', plan.sameTitles, 'titles'],
    ['treesSkipped', plan.treesSkipped, 'names'],
    ['unbound', plan.unbound, 'names'],
    ['videosAgain', plan.videosAgain, 'names'],
    ['tooLarge', plan.tooLarge, 'titles'],
    ['orphans', plan.orphans, 'names'],
    ['links', (checked?.profilePlan?.links ?? []).map((l) => l.path), 'paths'],
  ] as const).filter(([, list]) => list.length > 0) : []
  return (
    <>
      <input ref={input} type="file" accept="application/json,.json" className="sr-only" tabIndex={-1} aria-hidden
        data-testid="sponsor-package-file" onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) void chosen(file)
        }} />
      <ConfirmDialog open={large !== null} onOpenChange={(value) => !value && setLarge(null)}
        title={t('package.largeTitle')} description={large ? t('package.largeText', { size: formatBytes(large.size) })
          : undefined} confirmLabel={t('package.largeConfirm')} cancelLabel={t('package.cancel')}
        onConfirm={() => {
          if (large) download(large)
          setLarge(null)
        }} />
      <ConfirmDialog open={abandoning !== null} onOpenChange={(value) => !value && setAbandoning(null)}
        title={t('package.abandonTitle')} description={t('package.abandonText')} confirmLabel={t('package.abandonConfirm')}
        cancelLabel={t('package.cancel')} onConfirm={() => {
          if (abandoning) void abandon(abandoning)
        }} />
      <Dialog open={state !== null} onOpenChange={(value) => {
        if (!value && !busy) setState(null)
      }}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl" data-testid="sponsor-package-dialog">
          <DialogHeader>
            <DialogTitle>{t('package.importTitle')}</DialogTitle>
            <DialogDescription>{t('package.importHint')}</DialogDescription>
          </DialogHeader>
          <p className="text-muted-foreground text-sm">{t('package.reviewNote')}</p>
          {state?.kind === 'checking' && <p role="status" className="text-sm">{t('package.checking')}</p>}
          {state?.kind === 'refused' && (
            <Alert variant="destructive" data-testid="sponsor-package-refused">
              <AlertDescription>
                <ul className="list-disc pl-5">{state.problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
              </AlertDescription>
            </Alert>
          )}
          {state?.kind === 'checked' && plan && checked && (
            <div className="flex flex-col gap-3 text-sm" data-testid="sponsor-package-plan" data-mode={plan.mode}>
              <p className="text-muted-foreground">{t('package.version', { version: checked.document.version })}</p>
              {profileOnly && <Alert data-testid="sponsor-package-profile-only">
                <AlertDescription>{t('package.profileOnly')}</AlertDescription></Alert>}
              <div>
                <p className="font-medium">{t('package.makes')}</p>
                <ul className="list-disc pl-5" data-testid="sponsor-package-makes">
                  {plan.makes.profile && <li>{t('package.makesProfile')}</li>}
                  {!profileOnly && <>
                    <li data-testid="sponsor-package-makes-trees">{t('package.makesTrees', { count: plan.makes.trees })}</li>
                    <li data-testid="sponsor-package-makes-quizzes">{t('package.makesQuizzes',
                      { count: plan.makes.quizzes, versions: plan.makes.versions })}</li>
                    <li data-testid="sponsor-package-makes-publications">{t('package.makesPublications',
                      { count: plan.makes.publications })}</li>
                  </>}
                </ul>
              </div>
              {plan.treesKept && <p data-testid="sponsor-package-trees-kept">{t('package.treesKept')}</p>}
              {notices.map(([key, list, param]) => (
                <p key={key} data-testid={`sponsor-package-${key}`}>{t(`package.${key}`, { [param]: list.join('、') })}</p>
              ))}
              {plan.batchBytes > 0 && !profileOnly && (
                <p className="text-muted-foreground" data-testid="sponsor-bank-batch-info">
                  {t('package.batchInfo', { questions: plan.batchSize, megabytes: megabytes(plan.batchBytes) })}</p>
              )}
              {plan.oversized.length > 0 && (
                <Alert variant="destructive" data-testid="sponsor-package-oversized">
                  <AlertDescription>
                    {t('package.oversized', { megabytes: megabytes(plan.batchBytes), items: plan.oversized
                      .map((o) => o.question === null ? t('package.oversizedHead', { title: o.title })
                        : t('package.oversizedQuestion', { title: o.title, n: o.question })).join('、') })}
                  </AlertDescription>
                </Alert>
              )}
              {checked.problems.length > 0 && (
                <Alert variant="destructive" data-testid="sponsor-package-problems">
                  <AlertDescription className="flex flex-col gap-1">
                    <span>{t('package.problems')}</span>
                    <ul className="list-disc pl-5">{checked.problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
                  </AlertDescription>
                </Alert>
              )}
              {!profileOnly && <p className="text-muted-foreground">{t('package.abandonNote')}</p>}
              <p className="text-muted-foreground">{t('package.reviewNote')}</p>
              {needsPassword && (
                <div className="flex flex-col gap-2">
                  <Label htmlFor={passwordId}>{t('package.password')}</Label>
                  <Input id={passwordId} type="password" autoComplete="off" value={password}
                    data-testid="sponsor-package-password" onChange={(event) => setPassword(event.target.value)} />
                </div>
              )}
            </div>
          )}
          {state?.kind === 'importing' && (
            <p role="status" className="text-sm" data-testid="sponsor-package-progress">
              {t('package.progress', { index: state.index, total: state.total, step: state.label })}
            </p>
          )}
          {state?.kind === 'failed' && (
            <Alert variant="destructive" data-testid="sponsor-package-failed">
              <AlertDescription className="flex flex-col gap-1">
                <span>{t('package.failed', { step: state.label })}</span>
                <ul className="list-disc pl-5">{state.problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
                <span>{t('package.failedHint')}</span>
                {state.emptied && <span data-testid="sponsor-package-emptied">{t('package.emptyDraft')}</span>}
              </AlertDescription>
            </Alert>
          )}
          {state?.kind === 'done' && (
            <div className="flex flex-col gap-2 text-sm" data-testid="sponsor-package-done">
              <p className="font-medium">{t('package.done')}</p>
              {state.applyForm && (
                <p>{t('package.toBasics')}{' '}
                  <Link to={profilePath('basics')} className="text-primary underline underline-offset-4"
                    data-testid="sponsor-package-basics">{t('package.toBasicsLink')}</Link></p>
              )}
              {state.checked.plan.mode === 'FULL' && <>
                <p>{t('package.doneHint')}</p>
                <span><SubmitAllDraftsButton size="sm" /></span>
              </>}
            </div>
          )}
          <DialogFooter>
            {state?.kind === 'failed' && (
              <Button variant="outline" data-testid="sponsor-package-give-up" onClick={() => void readOpenImport()
                .then((openImport) => openImport && setAbandoning(openImport.importId))}>{t('package.abandon')}</Button>
            )}
            <Button variant="outline" disabled={busy} onClick={() => setState(null)}>
              {state?.kind === 'done' ? t('package.close') : t('package.cancel')}
            </Button>
            {state?.kind === 'checked' && (
              <Button data-testid="sponsor-package-confirm"
                disabled={state.checked.problems.length > 0 || state.checked.plan.oversized.length > 0
                  || (needsPassword && !password)}
                onClick={() => void start(state.checked)}>
                {profileOnly ? t('package.confirmProfile') : t('package.confirm')}
              </Button>
            )}
            {state?.kind === 'failed' && (
              <Button data-testid="sponsor-package-continue" onClick={() => void start(state.checked)}>
                {t('package.continue')}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

/**
 * An import stopped half-way (F-C4), told on the overview itself, not in the menu: it goes on with the same file
 * (the menu's import) or is given up here.
 */
export function OpenImportNotice({ onAbandon }: { onAbandon?: (importId: string) => void }) {
  const { t } = useTranslation(SPONSOR)
  const open = useQuery({ queryKey: OPEN_IMPORT_QUERY, queryFn: readOpenImport })
  if (!open.data) return null
  const importId = open.data.importId
  return (
    <Alert data-testid="sponsor-package-open">
      <AlertDescription className="flex flex-col gap-2">
        <span>{t('package.open', { done: open.data.stepsDone, total: open.data.stepsTotal })}</span>
        <span>
          <Button variant="outline" size="sm" data-testid="sponsor-package-abandon"
            onClick={() => onAbandon?.(importId)}>{t('package.abandon')}</Button>
        </span>
      </AlertDescription>
    </Alert>
  )
}
