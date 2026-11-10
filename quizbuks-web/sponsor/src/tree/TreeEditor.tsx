import { ApiError, i18n } from '@jabiz/client'
import { Alert, AlertDescription, Button, Card, CardContent, Input, Label, notify, PageState, Textarea } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import type { I18nText } from '../qb/legal'
import { pickText } from '../qb/legal'
import { problemText } from '../qb/problems'
import { TREE } from '../qb/ids'
import {
  readReferenceableNodes, readSponsorVersion, saveMapDraft, submitMap, withdrawMap, type SponsorMap, type TreeProblem,
} from '../qb/tree/api'
import { ConditionEditor, type ForeignNode } from './ConditionEditor'
import {
  afterSave, referrers, saveInputOf, withMoved, withNewNode, withNode, withoutNode, type Draft, type DraftNode,
} from './draft'
import './messages'

/** The interface language's code (the editor writes the texts of this language; the others are kept as they are). */
const language = () => i18n.language.slice(0, 2)

/** One text of an `I18nText` set in the interface's language; the others kept. */
function withText(text: I18nText | null, value: string): I18nText | null {
  const next = { ...(text ?? {}), [language()]: value }
  if (!value) delete next[language()]
  return Object.keys(next).length > 0 ? next : null
}

/** The version the editor starts from: the open one (a draft, in review, or sent back), else the one in effect. */
function startingVersion(map: SponsorMap): string | null {
  return map.openVersionId ?? map.currentVersionId
}

const changed = (error: unknown) => error instanceof ApiError
  && error.violations.some((violation) => violation.ruleCode === TREE.changedCode)

/**
 * The Sponsor's knowledge map editor (F-S3d, docs/quizbuks/plans/impl/F-S3.md §5): the whole draft is saved with each
 * save (`QB_TREE_DRAFT_SAVE`), which gives every problem the rules find, by node; submitting asks the operator's review
 * (another member), and a draft in review can be taken back. Saving a version in review takes it back too (the server
 * says so). A node with publications still running cannot be removed. A change made elsewhere is reported, and the page
 * is read again before anything more is sent.
 */
export function TreeEditor({ map, onClose }: { map: SponsorMap; onClose: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const versionId = startingVersion(map)
  const version = useQuery({ queryKey: ['qb', 'sponsor-map-edit', map.treeId, versionId],
    queryFn: () => readSponsorVersion(map, versionId!), enabled: !!versionId })
  if (versionId && version.isPending) return <PageState kind="loading" />
  if (version.isError) return <PageState kind="error" description={problemText(version.error)} />
  const nodes: DraftNode[] = (version.data?.graph.nodes ?? []).map((node) => ({ nodeId: node.nodeId, key: node.nodeId,
    name: node.name, description: node.description, condition: node.condition,
    runningBound: version.data?.runningBound.get(node.nodeId) ?? 0,
    certificateNames: version.data?.certificateNames.get(node.nodeId) ?? null }))
  const draft: Draft = { treeId: map.treeId, revision: map.revision, name: map.name, description: map.description,
    nodes }
  return <EditorForm key={`${map.treeId}:${map.revision}`} initial={draft} map={map} onClose={onClose} t={t} />
}

function EditorForm({ initial, map, onClose, t }: { initial: Draft; map: SponsorMap; onClose: () => void;
  t: (key: string, options?: Record<string, unknown>) => string }) {
  const id = useId()
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState(initial)
  const [problems, setProblems] = useState<TreeProblem[] | null>(null)
  const [saved, setSaved] = useState(map.openStatus === 'DRAFT' || map.openStatus === 'REJECTED')
  const [inReview, setInReview] = useState(map.openStatus === 'REVIEW')
  const [stale, setStale] = useState(false)
  const [dirty, setDirty] = useState(false)
  // F-S3e: the platform's nodes Takers light that the map may name (the draft's rules accept the same).
  const referenceable = useQuery({ queryKey: ['qb', 'sponsor-map-referenceable'], queryFn: readReferenceableNodes,
    staleTime: 60_000 })
  const foreign: ForeignNode[] = (referenceable.data ?? []).map((node) => ({ key: node.nodeId,
    name: pickText(node.name, i18n.language).text || t('tree.edit.unnamed'),
    mapName: pickText(node.treeName, i18n.language).text || t('tree.edit.unnamed') }))
  const nameOf = (node: string) => {
    const found = draft.nodes.find((n) => n.key === node || n.nodeId === node)
    if (found) return pickText(found.name, i18n.language).text || t('tree.edit.unnamed')
    const other = foreign.find((n) => n.key === node)
    return other ? t('tree.edit.platformNode', { name: other.name, map: other.mapName }) : t('tree.edit.otherMap')
  }
  const edit = (next: Draft) => {
    setDraft(next)
    setDirty(true)
  }
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['qb', 'sponsor-maps'] })
  const onError = (error: unknown) => {
    if (changed(error)) setStale(true)
  }

  const save = useMutation({
    mutationFn: () => saveMapDraft(saveInputOf(draft) as unknown as Record<string, unknown>),
    onSuccess: (out) => {
      setDraft((d) => afterSave(d, out.nodeIds ?? {}, out.revision))
      setProblems(out.problems ?? [])
      setSaved(true)
      setInReview(false)
      setDirty(false)
      notify.success(t('tree.edit.savedNotice'))
    },
    onError,
  })
  const submit = useMutation({
    mutationFn: () => submitMap(draft.treeId, draft.revision),
    onSuccess: (out) => {
      setDraft((d) => ({ ...d, revision: out.revision }))
      setInReview(out.status === 'REVIEW')
      notify.success(out.status === 'ACTIVE' ? t('tree.edit.activeNotice') : t('tree.edit.submittedNotice'))
      void refresh()
      if (out.status === 'ACTIVE') onClose()
    },
    onError,
  })
  const withdraw = useMutation({
    mutationFn: () => withdrawMap(draft.treeId, draft.revision),
    onSuccess: (out) => {
      setDraft((d) => ({ ...d, revision: out.revision }))
      setInReview(false)
      notify.success(t('tree.edit.withdrawnNotice'))
      void refresh()
    },
    onError,
  })
  const busy = save.isPending || submit.isPending || withdraw.isPending
  const locked = busy || stale
  const error = [save.error, submit.error, withdraw.error].find((e) => e && !changed(e))
  const byNode = (key: string) => (problems ?? []).filter((problem) => problem.nodeId === key)
  const ofGraph = (problems ?? []).filter((problem) => !problem.nodeId
    || !draft.nodes.some((node) => node.nodeId === problem.nodeId))
  const problemLine = (problem: TreeProblem) => t(`tree.problems.${problem.code}`, {
    ...problem.params,
    node: typeof problem.params.node === 'string' ? nameOf(problem.params.node) : problem.params.node,
    nodes: Array.isArray(problem.params.nodes) ? problem.params.nodes.map((n) => nameOf(String(n))).join(' → ') : '',
    defaultValue: problem.code,
  })
  const removeNode = (node: DraftNode) => {
    const named = referrers(draft, node.key)
    const message = named.length > 0
      ? t('tree.edit.removeConfirmNamed', { name: nameOf(node.key), names: named.map((n) => nameOf(n.key)).join(', ') })
      : t('tree.edit.removeConfirm', { name: nameOf(node.key) })
    if (!window.confirm(message)) return
    const next = withoutNode(draft, node.key)
    if (next) edit(next)
  }

  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="tree-editor">
      <h2 id={`${id}-title`} className="text-lg font-semibold">{t('tree.edit.title')}</h2>
      {stale && (
        <Alert variant="destructive" data-testid="tree-editor-stale">
          <AlertDescription className="flex flex-wrap items-center gap-2">
            {t('tree.edit.stale')}
            <Button type="button" variant="outline" size="sm" onClick={() => { void refresh(); onClose() }}>
              {t('tree.edit.reload')}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {inReview && <Alert data-testid="tree-editor-in-review"><AlertDescription>{t('tree.edit.inReview')}</AlertDescription></Alert>}
      {map.openStatus === 'REJECTED' && map.rejectReason && (
        <Alert variant="destructive" data-testid="tree-editor-rejected">
          <AlertDescription>{t('tree.edit.rejected', { reason: map.rejectReason })}</AlertDescription>
        </Alert>
      )}
      <Card><CardContent className="flex flex-col gap-3 pt-6">
        <Label htmlFor={`${id}-name`}>{t('tree.edit.mapName')}</Label>
        <Input id={`${id}-name`} value={draft.name[language()] ?? ''} disabled={locked} data-testid="tree-editor-name"
          onChange={(event) => edit({ ...draft, name: withText(draft.name, event.target.value) ?? {} })} />
        <Label htmlFor={`${id}-description`}>{t('tree.edit.mapDescription')}</Label>
        <Textarea id={`${id}-description`} value={draft.description?.[language()] ?? ''} disabled={locked}
          onChange={(event) => edit({ ...draft, description: withText(draft.description, event.target.value) })} />
        <p className="text-muted-foreground text-sm">{t('tree.edit.languageHint')}</p>
      </CardContent></Card>
      {ofGraph.length > 0 && (
        <Alert variant="destructive" data-testid="tree-editor-problems">
          <AlertDescription><ul className="list-disc pl-4">{ofGraph.map((p, i) => <li key={i}>{problemLine(p)}</li>)}</ul></AlertDescription>
        </Alert>
      )}
      <ol className="flex flex-col gap-3">
        {draft.nodes.map((node, index) => {
          const nodeProblems = node.nodeId ? byNode(node.nodeId) : []
          const others = draft.nodes.filter((other) => other.key !== node.key)
            .map((other) => ({ key: other.key, name: nameOf(other.key) }))
          return (
            <li key={node.key}>
              <Card data-testid="tree-editor-node" data-node={node.key}><CardContent className="flex flex-col gap-3 pt-6">
                <div className="flex flex-wrap items-end gap-2">
                  <div className="flex min-w-48 flex-1 flex-col gap-1.5">
                    <Label htmlFor={`${id}-node-${index}`}>{t('tree.edit.nodeName', { n: index + 1 })}</Label>
                    <Input id={`${id}-node-${index}`} value={node.name[language()] ?? ''} disabled={locked}
                      data-testid="tree-editor-node-name"
                      onChange={(event) => edit(withNode(draft, node.key,
                        (n) => ({ ...n, name: withText(n.name, event.target.value) ?? {} })))} />
                  </div>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('tree.edit.moveUp')}
                    disabled={locked || index === 0} onClick={() => edit(withMoved(draft, node.key, -1))}>
                    <ArrowUp aria-hidden className="size-4" />
                  </Button>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('tree.edit.moveDown')}
                    disabled={locked || index === draft.nodes.length - 1} onClick={() => edit(withMoved(draft, node.key, 1))}>
                    <ArrowDown aria-hidden className="size-4" />
                  </Button>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('tree.edit.removeNode')}
                    disabled={locked || node.runningBound > 0} data-testid="tree-editor-remove"
                    aria-describedby={node.runningBound > 0 ? `${id}-inuse-${index}` : undefined}
                    onClick={() => removeNode(node)}>
                    <Trash2 aria-hidden className="size-4" />
                  </Button>
                </div>
                {node.runningBound > 0 && (
                  <p id={`${id}-inuse-${index}`} className="text-muted-foreground text-sm" data-testid="tree-editor-in-use">
                    {t('tree.edit.inUse', { count: node.runningBound })}
                  </p>
                )}
                <Label htmlFor={`${id}-node-description-${index}`}>{t('tree.edit.nodeDescription')}</Label>
                <Textarea id={`${id}-node-description-${index}`} value={node.description?.[language()] ?? ''}
                  disabled={locked} placeholder={t('tree.edit.nodeDescriptionHint')}
                  onChange={(event) => edit(withNode(draft, node.key,
                    (n) => ({ ...n, description: withText(n.description, event.target.value) })))} />
                {/* F-S5b: lighting a node with a certificate name issues a certificate in that name (issuer: this company). */}
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor={`${id}-node-certificate-${index}`}>{t('tree.edit.certificate')}</Label>
                  <Input id={`${id}-node-certificate-${index}`} value={node.certificateNames?.[language()] ?? ''}
                    disabled={locked} data-testid="tree-editor-node-certificate"
                    aria-describedby={`${id}-node-certificate-hint-${index}`}
                    onChange={(event) => edit(withNode(draft, node.key, (n) => ({ ...n,
                      certificateNames: withText(n.certificateNames ?? null, event.target.value) })))} />
                  <p id={`${id}-node-certificate-hint-${index}`} className="text-muted-foreground text-sm">
                    {t('tree.edit.certificateHint')}
                  </p>
                </div>
                <ConditionEditor condition={node.condition} others={others} foreign={foreign} nameOf={nameOf}
                  disabled={locked}
                  onChange={(condition) => edit(withNode(draft, node.key, (n) => ({ ...n, condition })))} />
                {nodeProblems.length > 0 && (
                  <ul className="text-destructive list-disc pl-4 text-sm" data-testid="tree-editor-node-problems">
                    {nodeProblems.map((problem, i) => <li key={i}>{problemLine(problem)}</li>)}
                  </ul>
                )}
              </CardContent></Card>
            </li>
          )
        })}
      </ol>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" disabled={locked} data-testid="tree-editor-add"
          onClick={() => edit(withNewNode(draft, { [language()]: t('tree.edit.newNode') }))}>
          <Plus aria-hidden className="size-4" />{t('tree.edit.addNode')}
        </Button>
      </div>
      {error ? <Alert variant="destructive" data-testid="tree-editor-error"><AlertDescription>{problemText(error)}</AlertDescription></Alert> : null}
      <div className="flex flex-wrap gap-2">
        <Button type="button" disabled={locked} data-testid="tree-editor-save" onClick={() => save.mutate()}>
          {t('tree.edit.save')}
        </Button>
        <Button type="button" variant="secondary" data-testid="tree-editor-submit"
          disabled={locked || dirty || !saved || inReview || (problems?.length ?? 0) > 0}
          onClick={() => submit.mutate()}>
          {t('tree.edit.submit')}
        </Button>
        {inReview && (
          <Button type="button" variant="outline" disabled={locked} data-testid="tree-editor-withdraw"
            onClick={() => withdraw.mutate()}>
            {t('tree.edit.withdraw')}
          </Button>
        )}
        <Button type="button" variant="ghost" disabled={busy} data-testid="tree-editor-close"
          onClick={() => {
            if (!dirty || window.confirm(t('tree.edit.discard'))) onClose()
          }}>
          {t('tree.edit.close')}
        </Button>
      </div>
      {dirty && saved && <p className="text-muted-foreground text-sm">{t('tree.edit.saveFirst')}</p>}
    </section>
  )
}
