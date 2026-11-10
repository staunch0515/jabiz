import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useCallback, useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  ApprovalPanel,
  Badge,
  Button,
  DataTable,
  DescriptionList,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Label,
  notify,
  PageHeader,
  PageState,
  Textarea,
  UserName,
  useAuth,
  useLanguage,
  type ColumnDef,
  type DescriptionItem,
} from '@jabiz/admin'
import { AfterDecision, EmptyState, LoadFailed, Section, StatusBadge } from './components'
import { useAfterDecision, useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { TreeOwner } from './TreesPage'
import { useConditionText, useOperatorTrees } from './trees-hooks'
import {
  i18nText,
  parseCondition,
  readTree,
  readTreeLit,
  readTreeVersion,
  revokeLit,
  TREES_KEY,
  type LitRecord,
  type VersionNode,
} from './trees-api'

const REVIEW = 'REVIEW'
const REASON_MAX = 500
const CHANGE_VARIANT: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  ADDED: 'default', CHANGED: 'secondary', REMOVED: 'destructive', SAME: 'outline',
}

function NodesTable({ nodes, title }: { nodes: VersionNode[]; title: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const language = useLanguage()
  const conditionText = useConditionText()
  const names = useMemo(() => new Map(nodes.map((node) => [node.nodeId, i18nText(node.name, language)])), [nodes,
    language])
  const nameOf = useCallback((nodeId: string) => names.get(nodeId) || nodeId, [names])
  const columns = useMemo<ColumnDef<VersionNode, unknown>[]>(() => [
    { id: 'node', header: t('trees.nodes.node'), cell: ({ row }) => (
      <span className="flex flex-col">
        <span className="font-medium">{i18nText(row.original.name, language) || row.original.nodeId}</span>
        {row.original.description && (
          <span className="text-muted-foreground text-sm">{i18nText(row.original.description, language)}</span>
        )}
      </span>
    ) },
    { id: 'change', header: t('trees.nodes.change'), cell: ({ row }) => (
      <Badge variant={CHANGE_VARIANT[row.original.change] ?? 'outline'} data-testid="tree-node-change"
        data-change={row.original.change}>
        {t(`trees.nodes.changes.${row.original.change}`, { defaultValue: row.original.change })}
      </Badge>
    ) },
    { id: 'condition', header: t('trees.nodes.condition'), cell: ({ row }) => (
      <span data-testid="tree-node-condition">
        {conditionText(parseCondition(row.original.condition), nameOf)}
      </span>
    ) },
    { id: 'running', header: t('trees.nodes.running'), cell: ({ row }) => {
      const removed = row.original.change === 'REMOVED' && row.original.runningBound > 0
      return removed ? (
        <span className="text-destructive font-medium" data-testid="tree-node-running-removed">
          {t('trees.nodes.runningRemoved', { count: row.original.runningBound })}
        </span>
      ) : <span data-testid="tree-node-running">{row.original.runningBound}</span>
    } },
    { id: 'unlit', header: t('trees.nodes.unlit'), cell: ({ row }) => (
      <span className="flex flex-col" data-testid="tree-node-unlit">
        {row.original.countedOn == null ? <span className="text-muted-foreground">{t('trees.nodes.unlitNone')}</span>
          : (
            <>
              <span>{row.original.answeredUnlit ?? t('trees.nodes.unlitHidden')}</span>
              <span className="text-muted-foreground text-sm">{t('trees.nodes.unlitOn', { day: row.original.countedOn })}</span>
            </>
          )}
      </span>
    ) },
  ], [t, language, nameOf, conditionText])
  return (
    <DataTable label={title} columns={columns} data={nodes} getRowId={(row) => `${row.change}:${row.nodeId}`}
      empty={<EmptyState title={t('trees.nodes.none')} />}
      rowProps={(row) => ({ 'data-testid': 'tree-node', 'data-id': row.nodeId })} />
  )
}

function RevokeDialog({ lit, nodeName, onClose }: { lit: LitRecord; nodeName: string; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const text = reason.trim()
    setMissing(!text)
    if (!text || busy) return
    setBusy(true)
    setProblem(null)
    try {
      await revokeLit(lit.litId, text)
      notify.success(t('trees.revoke.done'))
      void queryClient.invalidateQueries({ queryKey: TREES_KEY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="tree-revoke-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('trees.revoke.title')}</DialogTitle>
            <DialogDescription>{t('trees.revoke.description')}</DialogDescription>
          </DialogHeader>
          <p className="text-sm"><UserName id={lit.subjectId} /> · {nodeName}</p>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-reason`}>{t('trees.revoke.reason')}</Label>
            <Textarea id={`${id}-reason`} value={reason} maxLength={REASON_MAX} required
              aria-invalid={missing ? true : undefined} aria-describedby={missing ? `${id}-reason-error` : undefined}
              data-testid="tree-revoke-reason" onChange={(event) => {
                setReason(event.target.value)
                setMissing(false)
              }} />
            {missing && <p id={`${id}-reason-error`} className="text-destructive text-sm">{t('trees.revoke.reasonRequired')}</p>}
          </div>
          {problem && (
            <Alert variant="destructive" data-testid="tree-revoke-problem">
              <AlertDescription>{t('trees.revoke.failed')}: {problem}</AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('trees.revoke.cancel')}</Button>
            <Button type="submit" disabled={busy || !reason.trim()} aria-busy={busy || undefined}
              data-testid="tree-revoke-submit">{t('trees.revoke.submit')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function LitSection({ operator, treeId, nameOf }: { operator: boolean; treeId: string;
  nameOf: (nodeId: string) => string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const subjectLabel = useLabels(DICTIONARY.treeLitSubject)
  const mayRevoke = can(PERMISSION.treeLitRevoke)
  const [revoking, setRevoking] = useState<LitRecord | null>(null)
  const { query, table } = usePaged([...TREES_KEY, 'lit', operator, treeId],
    (offset, limit) => readTreeLit(operator, treeId, offset, limit))
  const columns = useMemo<ColumnDef<LitRecord, unknown>[]>(() => [
    { id: 'subject', header: t('trees.lit.subject'), cell: ({ row }) => (row.original.subjectKind === 'USER'
      ? <UserName id={row.original.subjectId} />
      : <>{subjectLabel(row.original.subjectKind)} · <UserName id={row.original.subjectId} /></>) },
    { id: 'node', header: t('trees.lit.node'), cell: ({ row }) => nameOf(row.original.nodeId) },
    { id: 'litAt', header: t('trees.lit.litAt'), cell: ({ row }) => when(row.original.litAt) },
    { id: 'state', header: t('trees.lit.state'), cell: ({ row }) => {
      const lit = row.original
      if (lit.revokedAt) {
        return (
          <span className="flex flex-col" data-testid="tree-lit-revoked">
            <StatusBadge code="REVOKED" label={t('trees.lit.revoked')} />
            {lit.revokeReason && <span className="text-sm">{t('trees.lit.revokedBecause', { reason: lit.revokeReason })}</span>}
            <span className="text-muted-foreground text-sm">{when(lit.revokedAt)}</span>
          </span>
        )
      }
      if (lit.requestStatus === REVIEW) {
        return (
          <span className="flex flex-col" data-testid="tree-lit-in-review">
            <StatusBadge code={REVIEW} label={t('trees.lit.inReview')} />
            {lit.requestedBy && (
              <span className="text-muted-foreground text-sm">{t('trees.lit.requestedBy')}: <UserName id={lit.requestedBy} /></span>
            )}
          </span>
        )
      }
      return <StatusBadge code="ACTIVE" label={t('trees.lit.active')} />
    } },
    ...(mayRevoke ? [{ id: 'actions', header: '', cell: ({ row }: { row: { original: LitRecord } }) => (
      !row.original.revokedAt && row.original.requestStatus !== REVIEW ? (
        <Button variant="outline" size="sm" data-testid="tree-lit-revoke"
          aria-label={t('trees.lit.revokeFor', { node: nameOf(row.original.nodeId) })}
          onClick={() => setRevoking(row.original)}>{t('trees.lit.revoke')}</Button>
      ) : null
    ) }] : []),
  ], [t, subjectLabel, nameOf, mayRevoke])
  return (
    <Section title={t('trees.lit.title')} testId="tree-lit">
      <p className="text-muted-foreground text-sm">{t('trees.lit.hint')}</p>
      {query.isError ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} /> : (
        <DataTable label={t('trees.lit.table')} columns={columns} {...table} getRowId={(row) => row.litId}
          empty={<EmptyState title={t('trees.lit.none')} />}
          rowProps={(row) => ({ 'data-testid': 'tree-lit-row', 'data-id': row.litId })} />
      )}
      {revoking && <RevokeDialog lit={revoking} nodeName={nameOf(revoking.nodeId)} onClose={() => setRevoking(null)} />}
    </Section>
  )
}

/**
 * One knowledge map (F-S2b3a) as its reviewers see it: the version being edited or reviewed node by node against the
 * version in effect (what it adds, changes and removes, the conditions in words, and the running publications bound
 * to each node: those of a removed node could no longer start), the platform's `ApprovalPanel` while it waits for
 * review, and who lit which node, with the revocation asked for with a reason (`QB_TREE_LIT_REVOKE`, another member
 * approves it). An operator's member reads the same through the operator's templates (only the maps its operator
 * looks after), as the list does.
 */
export default function TreeReviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { treeId = '' } = useParams()
  const { can } = useAuth()
  const language = useLanguage()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const operator = useOperatorTrees()
  const statusLabel = useLabels(DICTIONARY.treeStatus)
  const versionLabel = useLabels(DICTIONARY.treeVersionStatus)
  const subjectLabel = useLabels(DICTIONARY.treeSubject)
  const tree = useQuery({
    queryKey: [...TREES_KEY, 'one', operator, treeId],
    queryFn: () => readTree(operator, treeId),
    refetchInterval: (query) => after.interval(query.state.data?.openStatus === REVIEW),
    refetchOnWindowFocus: false,
  })
  const shownVersion = tree.data ? (tree.data.openVersionId ?? tree.data.currentVersionId) : null
  const nodes = useQuery({
    queryKey: [...TREES_KEY, 'version', operator, shownVersion],
    queryFn: () => readTreeVersion(operator, shownVersion!),
    enabled: !!shownVersion,
    refetchOnWindowFocus: false,
  })
  const effect = tree.data?.currentVersionId ?? null
  // The lit records name the nodes in effect (a removed node by the open version's REMOVED row).
  const effectNodes = useQuery({
    queryKey: [...TREES_KEY, 'version', operator, effect],
    queryFn: () => readTreeVersion(operator, effect!),
    enabled: !!effect && effect !== shownVersion,
    refetchOnWindowFocus: false,
  })
  const names = useMemo(() => {
    const map = new Map<string, string>()
    for (const node of [...(effectNodes.data ?? []), ...(nodes.data ?? [])]) {
      map.set(node.nodeId, i18nText(node.name, language) || node.nodeId)
    }
    return map
  }, [nodes.data, effectNodes.data, language])
  const nameOf = useCallback((nodeId: string) => names.get(nodeId) ?? nodeId, [names])

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.trees}><ArrowLeft aria-hidden />{t('trees.back')}</Link>
    </Button>
  )
  if (tree.isPending) return <PageState kind="loading" />
  if (tree.isError) return <LoadFailed error={tree.error} onRetry={() => void tree.refetch()} />
  if (!tree.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('trees.notFound')} /></div>
  }
  const m = tree.data
  const name = i18nText(m.name, language) || t('trees.unnamed')
  const pending = m.openStatus === REVIEW && !!m.approvalRequestId
  // The decision is offered only on what the reviewer has read: every read of the page done, none failed.
  const readsDone = !shownVersion || (nodes.isSuccess && (effect === shownVersion || !effect
    || effectNodes.isSuccess))
  const readFailed = nodes.isError || effectNodes.isError
  const items: DescriptionItem[] = [
    { key: 'owner', label: t('trees.owner'), value: <TreeOwner tree={m} /> },
    { key: 'subject', label: t('trees.columns.subject'), value: subjectLabel(m.subjectKind) },
    { key: 'inEffect', label: t('trees.inEffect'), value: m.versionNo == null ? t('trees.noneInEffect')
      : t('trees.versionNo', { n: m.versionNo }) },
    { key: 'open', label: t('trees.openVersion'), value: m.openStatus ? (
      <span className="flex flex-wrap items-center gap-2">
        {t('trees.versionNo', { n: m.openVersionNo })}
        <StatusBadge code={m.openStatus} label={versionLabel(m.openStatus)} testId="tree-open-status" />
      </span>
    ) : t('trees.noOpen') },
    ...(m.submittedAt ? [{ key: 'submittedAt', label: t('trees.submittedAt'), value: when(m.submittedAt) }] : []),
    ...(m.remindedAt ? [{ key: 'remindedAt', label: t('trees.remindedAt'), value: when(m.remindedAt) }] : []),
    ...(m.templateTreeId ? [{ key: 'template', label: t('trees.template'), value: (
      <Link to={PATH.tree(m.templateTreeId)} className="text-primary font-medium underline-offset-4 hover:underline">
        {m.templateTreeId}</Link>
    ) }] : []),
    { key: 'nodes', label: t('trees.columns.nodes'), value: m.nodes },
    { key: 'lit', label: t('trees.columns.lit'), value: m.litSubjects },
  ]

  const decided = (status: string) => {
    after.decide(status)
    void tree.refetch()
    void queryClient.invalidateQueries({ queryKey: TREES_KEY })
  }
  let decision
  if (after.decided && (pending || after.levelOnly)) {
    decision = <AfterDecision after={after} onRetry={() => {
      after.retry()
      void tree.refetch()
    }} />
  } else if (!pending) {
    decision = <p data-testid="tree-not-pending">{m.openStatus
      ? t('trees.notPending', { status: versionLabel(m.openStatus) }) : t('trees.noOpen')}</p>
  } else if (!can(PERMISSION.reviewTree) && !can(PERMISSION.treePlatformWrite)) {
    decision = <p className="text-muted-foreground text-sm">{t('trees.notAllowed')}</p>
  } else if (readFailed) {
    decision = <LoadFailed error={nodes.error ?? effectNodes.error} onRetry={() => {
      void nodes.refetch()
      void effectNodes.refetch()
    }} />
  } else if (!readsDone) {
    decision = <p className="text-muted-foreground text-sm" data-testid="tree-wait-reads">{t('trees.waitReads')}</p>
  } else {
    decision = <ApprovalPanel requestId={m.approvalRequestId!} onDecided={decided} />
  }

  const shownNo = m.openVersionId ? m.openVersionNo : m.versionNo
  return (
    <div className="flex flex-col gap-4" data-testid="tree-review" data-status={m.openStatus ?? m.status}>
      <div>{back}</div>
      <PageHeader title={name} className="pb-0" description={
        <StatusBadge code={m.status} label={statusLabel(m.status)} testId="tree-status" />
      } />
      <Section title={t('trees.details')} testId="tree-details">
        <DescriptionList items={items} columns={2} />
      </Section>
      {shownVersion && (
        <Section title={m.openVersionId ? t('trees.nodes.title', { n: shownNo }) : t('trees.nodes.inEffectTitle')}
          testId="tree-nodes">
          {m.openVersionId && effect && <p className="text-muted-foreground text-sm">{t('trees.nodes.hint')}</p>}
          {nodes.isPending ? <PageState kind="loading" />
            : nodes.isError ? <LoadFailed error={nodes.error} onRetry={() => void nodes.refetch()} />
              : <NodesTable nodes={nodes.data} title={t('trees.nodes.table')} />}
        </Section>
      )}
      <Section title={t('trees.review')} testId="tree-decision">{decision}</Section>
      <LitSection operator={operator} treeId={m.treeId} nameOf={nameOf} />
    </div>
  )
}
