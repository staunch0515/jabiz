import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  AlertDescription,
  ApiError,
  Button,
  Checkbox,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  useLanguage,
} from '@jabiz/admin'
import { LoadFailed, Section } from './components'
import { money } from './hooks'
import { i18nText, readTreeVersion, saveTreeDraft, submitTree, TREES_KEY, withdrawTree, type TreeProblem,
  type TreeRow } from './trees-api'
import {
  afterSave, beforeAnswerAllowed, grantOf, isGroup, levelsOf, outer, passAllowed, referrers, saveInputOf, withBeforeAnswer,
  withItem, withItemAt, withMoved, withN, withNewNode, withNode, withOp, withoutItem, withoutNode, type Condition,
  type Draft, type DraftNode, type I18nText,
} from './tree-draft'

type Group = Extract<Condition, { op: string }>
const OPS: Group['op'][] = ['ALL', 'ANY', 'AT_LEAST']
/** The indentation of a level (literal classes: the stylesheet is built from them). */
const INDENT = ['ps-0', 'ps-6', 'ps-12', 'ps-18', 'ps-24'] as const
const CHANGED_CODE = 'QB_TREE_CHANGED'

function parse(json: string | null): I18nText | null {
  if (!json) return null
  try {
    return JSON.parse(json) as I18nText
  } catch {
    return null
  }
}

function parseCondition(json: string | null): Condition {
  try {
    return json ? (JSON.parse(json) as Condition) : { op: 'ALL', items: [{ passed: true }] }
  } catch {
    return { op: 'ALL', items: [{ passed: true }] }
  }
}

/**
 * The text-list editor of a platform template or an operator's copy (F-S3e, F-S3 §6: no third copy of the map
 * component in the extension): the draft's nodes by level, indented, each with its name, what lighting it gives and its
 * condition, with only what `tree.TreeRules` accepts in shape offered (`tree-draft.ts`, the Sponsor app's rules). The
 * whole draft is saved each time (`QB_TREE_DRAFT_SAVE`), the server's problems shown by node; submitting asks another
 * administrator (a template) or another member (a copy); a version in review can be taken back.
 */
export default function TreeEditor({ tree, operator, onClose }: { tree: TreeRow; operator: boolean;
  onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const versionId = tree.openVersionId ?? tree.currentVersionId
  const version = useQuery({
    queryKey: [...TREES_KEY, 'edit', operator, versionId, tree.revision],
    queryFn: () => readTreeVersion(operator, versionId!),
    enabled: !!versionId,
    refetchOnWindowFocus: false,
  })
  if (versionId && version.isPending) return <PageState kind="loading" />
  if (version.isError) return <LoadFailed error={version.error} onRetry={() => void version.refetch()} />
  const nodes: DraftNode[] = (version.data ?? []).filter((row) => row.change !== 'REMOVED').map((row) => ({
    nodeId: row.nodeId, key: row.nodeId, name: parse(row.name) ?? {}, description: parse(row.description),
    condition: parseCondition(row.condition), runningBound: row.runningBound, grantAmount: row.grantAmount,
    certificateNames: parse(row.certificateNames),
  }))
  const draft: Draft = { treeId: tree.treeId, revision: tree.revision, name: parse(tree.draftName) ?? {},
    description: parse(tree.description), nodes }
  return <EditorForm key={`${tree.treeId}:${tree.revision}`} initial={draft} tree={tree} onClose={onClose} t={t} />
}

function EditorForm({ initial, tree, onClose, t }: { initial: Draft; tree: TreeRow; onClose: () => void;
  t: (key: string, options?: Record<string, unknown>) => string }) {
  const id = useId()
  const language = useLanguage().slice(0, 2)
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState(initial)
  const [problems, setProblems] = useState<TreeProblem[] | null>(null)
  const [saved, setSaved] = useState(tree.openStatus === 'DRAFT' || tree.openStatus === 'REJECTED')
  const [inReview, setInReview] = useState(tree.openStatus === 'REVIEW')
  const [dirty, setDirty] = useState(false)
  const [stale, setStale] = useState(false)
  const levels = useMemo(() => levelsOf(draft.nodes), [draft.nodes])
  const ordered = useMemo(() => draft.nodes.map((node, index) => ({ node, index }))
    .sort((a, b) => (levels.get(a.node.key) ?? 0) - (levels.get(b.node.key) ?? 0) || a.index - b.index),
  [draft.nodes, levels])
  const text = (value: I18nText | null) => (value ? i18nText(JSON.stringify(value), language) : '')
  const nameOf = (key: string) => {
    const found = draft.nodes.find((node) => node.key === key || node.nodeId === key)
    return found ? text(found.name) || t('trees.editor.unnamed') : t('trees.editor.otherMap')
  }
  const withText = (value: I18nText | null, next: string): I18nText | null => {
    const texts = { ...(value ?? {}), [language]: next }
    if (!next) delete texts[language]
    return Object.keys(texts).length > 0 ? texts : null
  }
  const edit = (next: Draft) => {
    setDraft(next)
    setDirty(true)
  }
  const refresh = () => queryClient.invalidateQueries({ queryKey: TREES_KEY })
  const onError = (error: unknown) => {
    if (error instanceof ApiError && error.violations.some((v) => v.ruleCode === CHANGED_CODE)) setStale(true)
  }
  const save = useMutation({
    mutationFn: () => saveTreeDraft(saveInputOf(draft) as unknown as Record<string, unknown>),
    onSuccess: (out) => {
      setDraft((d) => afterSave(d, out.nodeIds ?? {}, out.revision))
      setProblems(out.problems ?? [])
      setSaved(true)
      setInReview(false)
      setDirty(false)
      notify.success(t('trees.editor.saved'))
      void refresh()
    },
    onError,
  })
  const submit = useMutation({
    mutationFn: () => submitTree(draft.treeId, draft.revision ?? 0),
    onSuccess: (out) => {
      setDraft((d) => ({ ...d, revision: out.revision }))
      setInReview(out.status === 'REVIEW')
      notify.success(out.status === 'ACTIVE' ? t('trees.editor.active') : t('trees.editor.submitted'))
      void refresh()
    },
    onError,
  })
  const withdraw = useMutation({
    mutationFn: () => withdrawTree(draft.treeId, draft.revision ?? 0),
    onSuccess: (out) => {
      setDraft((d) => ({ ...d, revision: out.revision }))
      setInReview(false)
      notify.success(t('trees.editor.withdrawn'))
      void refresh()
    },
    onError,
  })
  // F-S4b/F-S4c: a node of a platform template Sponsors light may grant platform fee Kudos; a copy shows its
  // template's (kept on save, never changed here). Other maps have none.
  const grants = tree.subjectKind !== 'SPONSOR' ? null : tree.ownerKind === 'PLATFORM' ? 'edit' : 'show'
  const busy = save.isPending || submit.isPending || withdraw.isPending
  const locked = busy || stale
  const failure = [save.error, submit.error, withdraw.error].find((e) => e && !(e instanceof ApiError
    && e.violations.some((v) => v.ruleCode === CHANGED_CODE)))
  const problemLine = (problem: TreeProblem) => t(`trees.problems.${problem.code}`, {
    ...problem.params,
    node: typeof problem.params.node === 'string' ? nameOf(problem.params.node) : problem.params.node,
    nodes: Array.isArray(problem.params.nodes) ? problem.params.nodes.map((n) => nameOf(String(n))).join(' → ') : '',
    defaultValue: problem.code,
  })
  const ofGraph = (problems ?? []).filter((p) => !p.nodeId || !draft.nodes.some((n) => n.nodeId === p.nodeId))
  const removeNode = (node: DraftNode) => {
    const named = referrers(draft, node.key)
    const message = named.length > 0
      ? t('trees.editor.removeConfirmNamed', { name: nameOf(node.key), names: named.map((n) => nameOf(n.key)).join(', ') })
      : t('trees.editor.removeConfirm', { name: nameOf(node.key) })
    if (!window.confirm(message)) return
    const next = withoutNode(draft, node.key)
    if (next) edit(next)
  }
  return (
    <Section title={t('trees.editor.title')} testId="tree-editor">
      <p className="text-muted-foreground text-sm">{t('trees.editor.hint')}</p>
      {stale && (
        <Alert variant="destructive" data-testid="tree-editor-stale">
          <AlertDescription className="flex flex-wrap items-center gap-2">
            {t('trees.editor.stale')}
            <Button type="button" variant="outline" size="sm" onClick={() => { void refresh(); onClose() }}>
              {t('trees.editor.reload')}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {inReview && <Alert data-testid="tree-editor-in-review"><AlertDescription>{t('trees.editor.inReview')}</AlertDescription></Alert>}
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${id}-name`}>{t('trees.editor.mapName')}</Label>
          <Input id={`${id}-name`} value={draft.name[language] ?? ''} disabled={locked} data-testid="tree-editor-name"
            onChange={(event) => edit({ ...draft, name: withText(draft.name, event.target.value) ?? {} })} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${id}-description`}>{t('trees.editor.mapDescription')}</Label>
          <Textarea id={`${id}-description`} value={draft.description?.[language] ?? ''} disabled={locked}
            onChange={(event) => edit({ ...draft, description: withText(draft.description, event.target.value) })} />
        </div>
      </div>
      <p className="text-muted-foreground text-sm">{t('trees.editor.languageHint')}</p>
      {ofGraph.length > 0 && (
        <Alert variant="destructive" data-testid="tree-editor-problems">
          <AlertDescription><ul className="list-disc ps-4">{ofGraph.map((p, i) => <li key={i}>{problemLine(p)}</li>)}</ul></AlertDescription>
        </Alert>
      )}
      <ol className="flex flex-col gap-3" aria-label={t('trees.editor.title')}>
        {ordered.map(({ node, index }) => {
          const level = levels.get(node.key) ?? 0
          const name = nameOf(node.key)
          const nodeProblems = (problems ?? []).filter((p) => node.nodeId && p.nodeId === node.nodeId)
          const others = draft.nodes.filter((other) => other.key !== node.key)
            .map((other) => ({ key: other.key, name: nameOf(other.key) }))
          return (
            <li key={node.key} className={INDENT[Math.min(level, INDENT.length - 1)]} data-testid="tree-editor-node"
              data-node={node.key} data-level={level + 1}>
              <div className="flex flex-col gap-2 rounded-md border p-3">
                <div className="flex flex-wrap items-end gap-2">
                  <span className="text-muted-foreground text-xs">{t('trees.editor.level', { n: level + 1 })}</span>
                  <div className="flex min-w-48 flex-1 flex-col gap-1">
                    <Label htmlFor={`${id}-node-${index}`}>{t('trees.editor.nodeName')}</Label>
                    <Input id={`${id}-node-${index}`} value={node.name[language] ?? ''} disabled={locked}
                      data-testid="tree-editor-node-name"
                      onChange={(event) => edit(withNode(draft, node.key,
                        (n) => ({ ...n, name: withText(n.name, event.target.value) ?? {} })))} />
                  </div>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('trees.editor.moveUp', { name })}
                    disabled={locked || index === 0} onClick={() => edit(withMoved(draft, node.key, -1))}>
                    <ArrowUp aria-hidden />
                  </Button>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('trees.editor.moveDown', { name })}
                    disabled={locked || index === draft.nodes.length - 1} onClick={() => edit(withMoved(draft, node.key, 1))}>
                    <ArrowDown aria-hidden />
                  </Button>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('trees.editor.removeNode', { name })}
                    disabled={locked || node.runningBound > 0} data-testid="tree-editor-remove"
                    onClick={() => removeNode(node)}>
                    <Trash2 aria-hidden />
                  </Button>
                </div>
                {node.runningBound > 0 && (
                  <p className="text-muted-foreground text-sm" data-testid="tree-editor-in-use">
                    {t('trees.editor.inUse', { count: node.runningBound })}
                  </p>
                )}
                <Label htmlFor={`${id}-node-description-${index}`}>{t('trees.editor.nodeDescription')}</Label>
                <Textarea id={`${id}-node-description-${index}`} value={node.description?.[language] ?? ''}
                  disabled={locked} onChange={(event) => edit(withNode(draft, node.key,
                    (n) => ({ ...n, description: withText(n.description, event.target.value) })))} />
                {grants === 'edit' && (
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor={`${id}-node-grant-${index}`}>{t('trees.editor.grant')}</Label>
                    <Input id={`${id}-node-grant-${index}`} type="number" inputMode="numeric" min={0} step={1}
                      className="w-40" value={node.grantAmount ?? ''} disabled={locked}
                      aria-describedby={`${id}-node-grant-hint-${index}`} data-testid="tree-editor-node-grant"
                      onChange={(event) => edit(withNode(draft, node.key, (n) => ({ ...n,
                        grantAmount: grantOf(event.target.value) })))} />
                    <p id={`${id}-node-grant-hint-${index}`} className="text-muted-foreground text-sm">
                      {t('trees.editor.grantHint')}
                    </p>
                  </div>
                )}
                {grants === 'show' && node.grantAmount != null && (
                  <p className="text-sm" data-testid="tree-editor-node-grant-copy">
                    {t('trees.editor.grantOfTemplate', { amount: money(String(node.grantAmount)) })}
                  </p>
                )}
                <GroupEditor group={outer(node.condition)} whole={node.condition} outermost others={others}
                  nameOf={nameOf} disabled={locked} t={t}
                  onChange={(condition) => edit(withNode(draft, node.key, (n) => ({ ...n, condition })))} />
                {nodeProblems.length > 0 && (
                  <ul className="text-destructive list-disc ps-4 text-sm" data-testid="tree-editor-node-problems">
                    {nodeProblems.map((problem, i) => <li key={i}>{problemLine(problem)}</li>)}
                  </ul>
                )}
              </div>
            </li>
          )
        })}
      </ol>
      <div>
        <Button type="button" variant="outline" disabled={locked} data-testid="tree-editor-add"
          onClick={() => edit(withNewNode(draft, { [language]: t('trees.editor.newNode') }))}>
          <Plus aria-hidden />{t('trees.editor.addNode')}
        </Button>
      </div>
      {failure ? (
        <Alert variant="destructive" data-testid="tree-editor-error">
          <AlertDescription>{t('trees.editor.failed')}: {failure instanceof ApiError ? failure.display : String(failure)}</AlertDescription>
        </Alert>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button type="button" disabled={locked} data-testid="tree-editor-save" onClick={() => save.mutate()}>
          {t('trees.editor.save')}
        </Button>
        <Button type="button" variant="secondary" data-testid="tree-editor-submit"
          disabled={locked || dirty || !saved || inReview || (problems?.length ?? 0) > 0}
          onClick={() => submit.mutate()}>{t('trees.editor.submit')}</Button>
        {inReview && (
          <Button type="button" variant="outline" disabled={locked} data-testid="tree-editor-withdraw"
            onClick={() => withdraw.mutate()}>{t('trees.editor.withdraw')}</Button>
        )}
        <Button type="button" variant="ghost" disabled={busy} data-testid="tree-editor-close"
          onClick={() => { if (!dirty || window.confirm(t('trees.editor.discard'))) onClose() }}>
          {t('trees.editor.close')}
        </Button>
      </div>
      {dirty && saved && <p className="text-muted-foreground text-sm">{t('trees.editor.saveFirst')}</p>}
    </Section>
  )
}

function GroupEditor({ group, whole, outermost, others, nameOf, disabled, onChange, t }: {
  group: Group
  whole: Condition
  outermost: boolean
  others: { key: string; name: string }[]
  nameOf: (key: string) => string
  disabled?: boolean
  onChange: (group: Group) => void
  t: (key: string, options?: Record<string, unknown>) => string
}) {
  const id = useId()
  const allowBefore = beforeAnswerAllowed(group, outermost)
  const named = new Set(group.items.flatMap((item) => (!isGroup(item) && 'node' in item ? [item.node] : [])))
  const free = others.filter((node) => !named.has(node.key))
  return (
    <fieldset className="flex flex-col gap-2 rounded-md border p-3" data-testid={outermost ? 'condition' : 'condition-group'}>
      <legend className="px-1 text-sm font-medium">{outermost ? t('trees.editor.condition') : t('trees.editor.group')}</legend>
      <div className="flex flex-wrap items-center gap-2">
        <Label htmlFor={`${id}-op`} className="sr-only">{t('trees.editor.op')}</Label>
        <Select value={group.op} disabled={disabled}
          onValueChange={(op) => onChange(withOp(group, op as Group['op'], outermost))}>
          <SelectTrigger id={`${id}-op`} className="w-48" data-testid="condition-op"><SelectValue /></SelectTrigger>
          <SelectContent>
            {OPS.map((op) => <SelectItem key={op} value={op}>{t(`trees.editor.ops.${op}`)}</SelectItem>)}
          </SelectContent>
        </Select>
        {group.op === 'AT_LEAST' && (
          <>
            <Label htmlFor={`${id}-n`} className="text-sm">{t('trees.editor.n')}</Label>
            <Input id={`${id}-n`} type="number" inputMode="numeric" min={1} max={Math.max(1, group.items.length)}
              className="w-20" value={group.n} disabled={disabled} data-testid="condition-n"
              onChange={(event) => onChange(withN(group, Number(event.target.value)))} />
          </>
        )}
      </div>
      {group.items.length === 0 && <p className="text-muted-foreground text-sm">{t('trees.editor.emptyGroup')}</p>}
      <ul className="flex flex-col gap-2">
        {group.items.map((item, index) => (
          <li key={index} className="flex flex-wrap items-center gap-2" data-testid="condition-item">
            {isGroup(item)
              ? (outermost
                ? <div className="w-full"><GroupEditor group={item} whole={whole} outermost={false} others={others}
                  nameOf={nameOf} disabled={disabled} t={t}
                  onChange={(next) => onChange(withItemAt(group, index, next))} /></div>
                : <span className="text-sm">{t('trees.editor.deeper')}</span>)
              : 'node' in item ? (
                <>
                  <span className="text-sm">{t('trees.editor.nodeItem', { name: nameOf(item.node) })}</span>
                  {allowBefore && (
                    <span className="flex items-center gap-1.5">
                      <Checkbox id={`${id}-before-${index}`} checked={item.beforeAnswer === true} disabled={disabled}
                        data-testid="condition-before-answer"
                        onCheckedChange={(on) => onChange(withItemAt(group, index, withBeforeAnswer(item, on === true, true)))} />
                      <Label htmlFor={`${id}-before-${index}`} className="text-sm font-normal">{t('trees.editor.beforeAnswer')}</Label>
                    </span>
                  )}
                </>
              ) : <span className="text-sm">{t('trees.editor.passItem')}</span>}
            <Button type="button" variant="ghost" size="icon" disabled={disabled}
              aria-label={t('trees.editor.removeItem')} data-testid="condition-remove"
              onClick={() => onChange(withoutItem(group, index))}>
              <Trash2 aria-hidden />
            </Button>
          </li>
        ))}
      </ul>
      <div className="flex flex-wrap items-center gap-2">
        {free.length > 0 && (
          <Select value="" disabled={disabled} onValueChange={(node) => onChange(withItem(group, { node }, whole))}>
            <SelectTrigger className="w-56" aria-label={t('trees.editor.addRef')} data-testid="condition-add-node">
              <SelectValue placeholder={t('trees.editor.addRef')} />
            </SelectTrigger>
            <SelectContent>
              {free.map((node) => <SelectItem key={node.key} value={node.key}>{node.name}</SelectItem>)}
            </SelectContent>
          </Select>
        )}
        {passAllowed(whole) && (
          <Button type="button" variant="outline" size="sm" disabled={disabled} data-testid="condition-add-pass"
            onClick={() => onChange(withItem(group, { passed: true }, whole))}>
            <Plus aria-hidden />{t('trees.editor.addPass')}
          </Button>
        )}
        {outermost && (
          <Button type="button" variant="outline" size="sm" disabled={disabled} data-testid="condition-add-group"
            onClick={() => onChange(withItem(group, { op: 'ANY', items: [] }, whole))}>
            <Plus aria-hidden />{t('trees.editor.addGroup')}
          </Button>
        )}
      </div>
    </fieldset>
  )
}
