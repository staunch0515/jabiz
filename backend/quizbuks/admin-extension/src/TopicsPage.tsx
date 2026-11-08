import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  PageHeader,
  paths,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { QUEUE, usePaged } from './hooks'
import { PERMISSION, TOPIC_PROCESS } from './ids'
import { readTopics, type TopicRow } from './topic-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { code: 'topicId', publications: 'publications', liked: 'likedBy' } as const
const LANGUAGES = ['zh', 'ja', 'en'] as const

/**
 * The word list of topics (F-S9; docs/quizbuks/operations.md §7.26), for headquarters: each word with its names in
 * every language, its state and its figures (publications with it; users who like it and of them with strict mode on,
 * those two only from the minimum group up). Adding, renaming, deactivating or offering again and merging are the
 * platform's process pages (with the word filled in), whose every change is audited.
 */
export default function TopicsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const write = can(PERMISSION.topicWrite)
  const { query, table, sortable } = usePaged([...QUEUE.topics], readTopics, true, SORTS)
  const minGroup = query.data?.items[0]?.minGroup ?? 5
  const columns = useMemo<ColumnDef<TopicRow, unknown>[]>(() => {
    const link = (process: string, topicId: string) =>
      `${paths.process(process, TOPIC_PROCESS.version)}?${new URLSearchParams({ topicId })}`
    const count = (value: number | null, min: number) => value ?? t('topics.fewer', { min })
    return [
      { id: 'code', header: t('topics.columns.code'),
        cell: ({ row }) => <code className="text-sm" data-testid="topic-code">{row.original.topicId}</code> },
      {
        id: 'names',
        header: t('topics.columns.names'),
        cell: ({ row }) => (
          <dl className="grid grid-cols-[auto_1fr] gap-x-2 text-sm">
            {LANGUAGES.map((language) => (
              <div key={language} className="contents">
                <dt className="text-muted-foreground">{t(`topics.languages.${language}`)}</dt>
                <dd lang={language} data-testid={`topic-name-${language}`}>{row.original.name[language] ?? ''}</dd>
              </div>
            ))}
          </dl>
        ),
      },
      {
        id: 'state',
        header: t('topics.columns.state'),
        cell: ({ row }) => {
          const { active, mergedInto } = row.original
          return (
            <StatusBadge code={active ? 'ACTIVE' : 'ENDED'} testId="topic-state"
              label={mergedInto ? t('topics.states.merged', { into: mergedInto })
                : t(active ? 'topics.states.active' : 'topics.states.inactive')} />
          )
        },
      },
      {
        id: 'publications',
        header: t('topics.columns.publications'),
        meta: { className: 'tabular-nums' },
        cell: ({ row }) => row.original.publications == null ? null : (
          <span className="flex flex-col" data-testid="topic-publications">
            <span>{row.original.publications}</span>
            <span className="text-muted-foreground text-xs">
              {t('topics.live', { count: row.original.livePublications ?? 0 })}</span>
          </span>
        ),
      },
      {
        id: 'liked',
        header: t('topics.columns.liked'),
        meta: { className: 'tabular-nums' },
        cell: ({ row }) => row.original.mergedInto ? null : (
          <span className="flex flex-col" data-testid="topic-liked">
            <span>{count(row.original.likedBy, row.original.minGroup)}</span>
            {row.original.likedBy != null && (
              <span className="text-muted-foreground text-xs">
                {t('topics.strict', { count: count(row.original.strictBy, row.original.minGroup) })}</span>
            )}
          </span>
        ),
      },
      {
        id: 'actions',
        header: t('topics.columns.actions'),
        cell: ({ row }) => write && !row.original.mergedInto && (
          <span className="flex flex-col items-start gap-1">
            <Link to={link(TOPIC_PROCESS.rename, row.original.topicId)} className={linkClass} data-testid="topic-rename"
              aria-label={t('topics.renameOf', { code: row.original.topicId })}>{t('topics.rename')}</Link>
            {row.original.active
              ? <Link to={link(TOPIC_PROCESS.deactivate, row.original.topicId)} className={linkClass}
                data-testid="topic-deactivate" aria-label={t('topics.deactivateOf', { code: row.original.topicId })}>
                {t('topics.deactivate')}</Link>
              : <Link to={link(TOPIC_PROCESS.reactivate, row.original.topicId)} className={linkClass}
                data-testid="topic-reactivate" aria-label={t('topics.reactivateOf', { code: row.original.topicId })}>
                {t('topics.reactivate')}</Link>}
            <Link to={link(TOPIC_PROCESS.merge, row.original.topicId)} className={linkClass} data-testid="topic-merge"
              aria-label={t('topics.mergeOf', { code: row.original.topicId })}>{t('topics.merge')}</Link>
          </span>
        ),
      },
    ]
  }, [t, write])
  return (
    <div className="flex flex-col gap-4" data-testid="qb-topics">
      <PageHeader title={t('topics.title')} description={t('topics.description')} className="pb-0"
        actions={write && (
          <Button asChild>
            <Link to={paths.process(TOPIC_PROCESS.add, TOPIC_PROCESS.version)} data-testid="topic-add">
              {t('topics.add')}</Link>
          </Button>
        )} />
      <p className="text-muted-foreground text-sm" data-testid="topic-min-group">
        {t('topics.minGroupHint', { min: minGroup })}</p>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : <DataTable label={t('topics.table')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.topicId} empty={<EmptyState art="search" title={t('topics.none')} />}
          rowProps={(row) => ({ 'data-testid': 'topic-row', 'data-id': row.topicId })} />}
    </div>
  )
}
