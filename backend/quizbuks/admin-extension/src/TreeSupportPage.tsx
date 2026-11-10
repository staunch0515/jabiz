import { useMutation, useQuery } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  useAuth,
  useLanguage,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, Section } from './components'
import { when } from './hooks'
import { PERMISSION } from './ids'
import { UserPicker } from './pickers'
import { useOperatorTrees } from './trees-hooks'
import { i18nText, readSupportTreeState, readTrees, TREES_KEY, type SupportState } from './trees-api'

const STATE_VARIANT: Record<string, 'default' | 'secondary' | 'outline'> = { LIT: 'default', OPEN: 'secondary',
  UNMET: 'outline' }

/**
 * Support's lookup of one person's lighting of one map (F-S3e, `QB_SUPPORT_TREE_STATE`, `qb.admin.tree-state.read`):
 * each node's state (lit, can be challenged, prerequisites not lit), what to light first, and the lit records with
 * their revocations. Every lookup is a recorded process run; an operator's member reads only its Sponsors' maps and
 * the platform's (another map is "not found"). A member without the users' page types the user's ID.
 */
export default function TreeSupportPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const language = useLanguage()
  const { can } = useAuth()
  const operator = useOperatorTrees()
  const [userId, setUserId] = useState<string | null>(null)
  const [typed, setTyped] = useState('')
  const [treeId, setTreeId] = useState('')
  const maps = useQuery({ queryKey: [...TREES_KEY, 'support-maps', operator],
    queryFn: () => readTrees(operator, 0, 200) })
  const lookup = useMutation({ mutationFn: (input: { userId: string; treeId: string }) =>
    readSupportTreeState(input.userId, input.treeId) })
  const who = can(PERMISSION.usersRead) ? userId : typed.trim() || null
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (who && treeId) lookup.mutate({ userId: who, treeId })
  }
  const notFound = lookup.error instanceof ApiError && lookup.error.status === 404
  return (
    <div className="flex flex-col gap-4" data-testid="qb-tree-support">
      <PageHeader title={t('trees.support.title')} description={t('trees.support.description')} className="pb-0" />
      <Section title={t('trees.support.title')}>
        <form noValidate onSubmit={submit} className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <div className="flex flex-col gap-2">
            {can(PERMISSION.usersRead) ? (
              <>
                <Label htmlFor={`${id}-user`}>{t('trees.support.user')}</Label>
                <UserPicker id={`${id}-user`} value={userId} onChange={setUserId} data-testid="tree-support-user" />
              </>
            ) : (
              <>
                <Label htmlFor={`${id}-user`}>{t('trees.support.userId')}</Label>
                <Input id={`${id}-user`} value={typed} autoComplete="off" data-testid="tree-support-user-id"
                  onChange={(event) => setTyped(event.target.value)} />
              </>
            )}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-map`}>{t('trees.support.map')}</Label>
            {maps.isError ? <LoadFailed error={maps.error} onRetry={() => void maps.refetch()} /> : (
              <Select value={treeId} onValueChange={setTreeId}>
                <SelectTrigger id={`${id}-map`} data-testid="tree-support-map"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {(maps.data?.items ?? []).map((map) => (
                    <SelectItem key={map.treeId} value={map.treeId}>
                      {i18nText(map.name, language) || t('trees.unnamed')}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </div>
          <Button type="submit" disabled={!who || !treeId || lookup.isPending} data-testid="tree-support-submit">
            {t('trees.support.submit')}
          </Button>
        </form>
      </Section>
      {lookup.isError && (
        <Alert variant="destructive" data-testid="tree-support-error">
          <AlertDescription>{notFound ? t('trees.support.notFound')
            : `${t('trees.support.failed')}: ${lookup.error instanceof ApiError ? lookup.error.display : String(lookup.error)}`}</AlertDescription>
        </Alert>
      )}
      {lookup.data && <StateTable state={lookup.data} />}
    </div>
  )
}

function StateTable({ state }: { state: SupportState }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const language = useLanguage()
  const names = new Map(state.nodes.map((node) => [node.nodeId,
    (node.name == null ? '' : i18nText(typeof node.name === 'string' ? node.name : JSON.stringify(node.name),
      language)) || node.nodeId]))
  const nameOf = (ref: string) => names.get(ref.split('/').pop() ?? ref) ?? ref
  if (state.nodes.length === 0) return <EmptyState title={t('trees.support.none')} />
  return (
    <Section title={t('trees.support.node')} testId="tree-support-result">
      <ul className="flex flex-col gap-3">
        {state.nodes.map((node) => (
          <li key={node.nodeId} className="flex flex-col gap-1 rounded-md border p-3" data-testid="tree-support-node"
            data-state={node.state}>
            <span className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{names.get(node.nodeId)}</span>
              <Badge variant={STATE_VARIANT[node.state] ?? 'outline'}>
                {t(`trees.support.states.${node.state}`, { defaultValue: node.state })}
              </Badge>
            </span>
            {node.unmet.length > 0 && (
              <span className="text-sm">{t('trees.support.unmet')}: {node.unmet.map(nameOf).join(', ')}</span>
            )}
            {node.lits.length > 0 && (
              <ul className="text-muted-foreground text-sm" aria-label={t('trees.support.lits')}>
                {node.lits.map((lit) => (
                  <li key={lit.litId}>
                    {t('trees.support.litAt', { at: when(lit.litAt) })}
                    {lit.revokedAt && <> · {t('trees.support.revoked', { at: when(lit.revokedAt), reason: lit.revokeReason ?? '' })}</>}
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </Section>
  )
}
