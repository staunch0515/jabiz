import { formatDateTime } from '@jabiz/client'
import { Badge, Button, Input, Label, PageHeader, PageState, SegmentedControl, Switch } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { CONVERSATIONS_PATH } from '../conversations/api'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { QB } from '../qb/messages'
import { Link, useLocation } from '../qb/router'
import { StateLabel, TechTree, type TechTreeNode } from '../qb/TechTree'
import { progressOf, type TreeState } from '../qb/tree'
import { publicationPath, PUBLICATIONS_PATH } from '../publishing/paths'
import { transactionsOf } from '../transactions/api'
import { nodeOf, PEOPLE_PAGE, PEOPLE_STATES, readPeople, readPerson, readTree, TREE_QUERY, USER_KEY,
  type TreeRow } from './api'
import './messages'

/**
 * The sponsor's tree (Q85, D-Q85-3 / D-Q85-4): every submitted publication as a node with how many customers lit it,
 * answered without passing, or could start it; a node picked lists the people on it by state (name and the
 * transactions page's key, nothing more, D-Q85-5); a customer's key pasted lights the whole tree as they see it.
 * Nothing here writes.
 */
export function TreePage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const { search } = useLocation()
  const [at] = useState(() => new Date().toISOString())
  const tree = useQuery({ queryKey: [...TREE_QUERY, at], queryFn: () => readTree(at) })
  const [hideEnded, setHideEnded] = useState(false)
  const [hideIsolated, setHideIsolated] = useState(false)
  const [selected, setSelected] = useState<string | null>(() => nodeOf(search))
  const [keyText, setKeyText] = useState('')
  const [userKey, setUserKey] = useState<string | null>(null)
  const [keyProblem, setKeyProblem] = useState<string | null>(null)
  const person = useQuery({ queryKey: [...TREE_QUERY, 'person', userKey, at],
    queryFn: () => readPerson(userKey!, at), enabled: !!userKey })

  const rows = useMemo(() => (tree.data ?? []).filter((row) => (!hideEnded || row.status !== 'ENDED')
    && (!hideIsolated || !row.isolated)), [tree.data, hideEnded, hideIsolated])
  const shownKeys = useMemo(() => new Set(rows.map((row) => row.id)), [rows])
  const lit = userKey && person.isSuccess && person.data ? person.data : null
  const nodes = useMemo<TechTreeNode[]>(() => rows.map((row) => ({
    id: row.id, name: row.name,
    requires: row.requires.filter((edge) => shownKeys.has(edge.id)),
    live: row.live,
    ...(lit ? { state: lit.states[row.id] ?? 'CLOSED' } : { counts: row.counts }),
  })), [rows, shownKeys, lit])
  const hasEdges = (tree.data ?? []).some((row) => row.requires.length > 0)
  const current = rows.find((row) => row.id === selected) ?? null

  const showPerson = (event: FormEvent) => {
    event.preventDefault()
    const key = keyText.trim().toLowerCase()
    if (!USER_KEY.test(key)) {
      setKeyProblem(t('tree.customer.bad'))
      return
    }
    setKeyProblem(null)
    setUserKey(key)
  }
  const clearPerson = () => {
    setUserKey(null)
    setKeyText('')
    setKeyProblem(null)
  }

  return (
    <div className="flex flex-col gap-4" data-testid="tree-page">
      <PageHeader title={t('tree.title')} description={t('tree.intro')} />
      {tree.isPending ? (
        <PageState kind="loading" />
      ) : tree.isError ? (
        <PageState kind="error" description={problemText(tree.error)}
          action={<Button variant="outline" onClick={() => void tree.refetch()}>{tQb('retry')}</Button>} />
      ) : tree.data.length === 0 ? (
        <PageState kind="empty" title={t('tree.empty')} data-testid="tree-empty"
          action={<Button asChild variant="outline"><Link to={PUBLICATIONS_PATH}>{t('tree.toPublications')}</Link></Button>} />
      ) : (
        <>
          {!hasEdges && (
            <p className="text-muted-foreground text-sm" data-testid="tree-no-edges">{t('tree.noEdges')}</p>
          )}
          <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
            <span className="flex items-center gap-2">
              <Switch id={`${id}-ended`} checked={hideEnded} onCheckedChange={setHideEnded} data-testid="tree-hide-ended" />
              <Label htmlFor={`${id}-ended`}>{t('tree.hideEnded')}</Label>
            </span>
            <span className="flex items-center gap-2">
              <Switch id={`${id}-isolated`} checked={hideIsolated} onCheckedChange={setHideIsolated}
                data-testid="tree-hide-isolated" />
              <Label htmlFor={`${id}-isolated`}>{t('tree.hideIsolated')}</Label>
            </span>
          </div>
          <form onSubmit={showPerson} className="flex flex-col gap-1" data-testid="tree-customer">
            <Label htmlFor={`${id}-key`}>{t('tree.customer.label')}</Label>
            <div className="flex flex-wrap gap-2">
              <Input id={`${id}-key`} value={keyText} maxLength={32} placeholder={t('tree.customer.placeholder')}
                className="w-80 max-w-full font-mono" aria-invalid={!!keyProblem} data-testid="tree-customer-key"
                onChange={(event) => setKeyText(event.target.value)} />
              <Button type="submit" variant="outline" data-testid="tree-customer-show">{t('tree.customer.show')}</Button>
              {userKey && (
                <Button type="button" variant="ghost" onClick={clearPerson} data-testid="tree-customer-clear">
                  {t('tree.customer.clear')}
                </Button>
              )}
            </div>
            <p className="text-destructive text-sm empty:hidden" role="alert" data-testid="tree-customer-problem">
              {keyProblem ?? (userKey && person.isSuccess && !person.data ? t('tree.customer.none') : '')}
            </p>
            {lit && (
              <p className="text-sm" data-testid="tree-customer-shown">
                {t('tree.customer.shown', { name: lit.userName ?? t('tree.customer.someone') })}
                {' · '}{tQb('tree.progress', { lit: progressOf(nodes).lit, total: nodes.length })}
              </p>
            )}
          </form>
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start">
            <TechTree nodes={nodes} selected={selected} onSelect={setSelected} label={t('tree.label')}
              className="min-w-0 flex-1" />
            <aside className="bg-card flex w-full flex-col gap-3 rounded-lg border p-4 lg:w-96 lg:shrink-0"
              aria-labelledby={`${id}-node`} data-testid="tree-node-panel">
              {current
                ? <NodePanel key={current.id} id={`${id}-node`} row={current} at={at}
                  onLight={(key) => {
                    setKeyText(key)
                    setKeyProblem(null)
                    setUserKey(key)
                  }} />
                : <p id={`${id}-node`} className="text-muted-foreground text-sm">{t('tree.node.pick')}</p>}
            </aside>
          </div>
        </>
      )}
    </div>
  )
}

/** One node: its status and counts, and the people on it in the state picked. */
function NodePanel({ id, row, at, onLight }: { id: string; row: TreeRow; at: string;
  onLight: (userKey: string) => void }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [state, setState] = useState<TreeState>('LIT')
  const [pages, setPages] = useState(1)
  const people = useQuery({ queryKey: [...TREE_QUERY, 'people', row.id, state, pages, at],
    queryFn: async () => {
      const all = []
      for (let i = 0; i < pages; i++) {
        const page = await readPeople(row.id, state, at, i * PEOPLE_PAGE)
        all.push(...page.items)
        if (page.items.length < PEOPLE_PAGE) break
      }
      return all
    } })
  const countOf = (s: TreeState) => s === 'LIT' ? row.counts.lit : s === 'ANSWERED' ? row.counts.answered
    : s === 'IN_PROGRESS' ? row.counts.inProgress : s === 'UNLOCKED' ? row.counts.unlocked
      : s === 'LOCKED' ? row.counts.locked : 0
  return (
    <>
      <h2 id={id} className="font-semibold" data-testid="tree-node-name">{row.name}</h2>
      <p className="text-muted-foreground flex flex-wrap items-center gap-2 text-sm">
        <Badge variant={row.live ? 'default' : 'secondary'} data-testid="tree-node-live">
          {row.live ? t('tree.node.open') : t('tree.node.closed')}
        </Badge>
        <span>{t('tree.node.counts', row.counts)}</span>
      </p>
      <Link to={publicationPath(row.id)} className="w-fit text-sm underline underline-offset-4"
        data-testid="tree-node-publication">{t('tree.node.publication')}</Link>
      <SegmentedControl value={state} aria-label={t('tree.node.status')} data-testid="tree-people-state"
        onChange={(next) => {
          setState(next)
          setPages(1)
        }}
        options={PEOPLE_STATES.map((s) => ({ value: s, label: `${tQb(`tree.state.${s}`)} ${countOf(s)}` }))} />
      {people.isPending ? (
        <PageState kind="loading" />
      ) : people.isError ? (
        <PageState kind="error" description={problemText(people.error)} />
      ) : people.data.length === 0 ? (
        <p className="text-muted-foreground text-sm" data-testid="tree-people-none">{t('tree.people.none')}</p>
      ) : (
        <ul className="flex flex-col gap-2" data-testid="tree-people">
          {people.data.map((person) => (
            <li key={person.userKey} className="flex flex-col gap-1 rounded-md border p-2 text-sm"
              data-testid="tree-person" data-key={person.userKey}>
              <span className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-medium">{person.userName ?? t('tree.people.someone')}</span>
                <StateLabel state={person.state} className="text-muted-foreground text-xs" />
              </span>
              <span className="text-muted-foreground font-mono text-xs break-all">{person.userKey}</span>
              {person.finishedAt && (
                <span className="text-muted-foreground text-xs">
                  {t('tree.people.when', { when: formatDateTime(person.finishedAt) })}
                </span>
              )}
              <span className="flex flex-wrap gap-3 text-xs">
                <button type="button" className="underline underline-offset-4" onClick={() => onLight(person.userKey)}
                  data-testid="tree-person-light">{t('tree.people.light')}</button>
                <Link to={`${transactionsOf(row.id)}&user=${encodeURIComponent(person.userKey)}`}
                  className="underline underline-offset-4" data-testid="tree-person-transactions">
                  {t('tree.people.transactions')}</Link>
                {person.hasConversation && (
                  <Link to={CONVERSATIONS_PATH} className="underline underline-offset-4"
                    data-testid="tree-person-conversation">{t('tree.people.conversation')}</Link>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {people.isSuccess && people.data.length === pages * PEOPLE_PAGE && (
        <Button type="button" variant="outline" size="sm" onClick={() => setPages((n) => n + 1)}
          data-testid="tree-people-more">{t('tree.people.more')}</Button>
      )}
    </>
  )
}
