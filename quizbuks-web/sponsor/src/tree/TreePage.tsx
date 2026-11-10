import { i18n } from '@jabiz/client'
import { Alert, AlertDescription, Badge, Button, PageState } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { pickText } from '../qb/legal'
import { QB } from '../qb/messages'
import { Link } from '../qb/router'
import { problemText } from '../qb/problems'
import {
  createSponsorMap, readSponsorMaps, readSponsorVersion, readSponsorVersions, type SponsorMap,
} from '../qb/tree/api'
import { TreeEditor } from './TreeEditor'
import { KnowledgeMap } from '../qb/tree/KnowledgeMap'
import { PUBLICATIONS_PATH } from '../publishing/paths'
import './messages'
import { AreaPageHeader } from '../shell/AreaPageHeader'

/**
 * The Sponsor's knowledge map (F-S3c, docs/quizbuks/plans/impl/F-S3.md §4; Q85's tree page before, at the same
 * address): each own map's version in effect drawn by the shared component with how many Takers lit each node (under
 * the minimum group, none: `qb.sponsor.tree`), the open version's state with the reviewer's reason, the versions,
 * and (F-S3d) the editor; a Sponsor without a map creates one.
 */
export function TreePage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const maps = useQuery({ queryKey: ['qb', 'sponsor-maps'], queryFn: readSponsorMaps })
  const queryClient = useQueryClient()
  const create = useMutation({
    mutationFn: () => createSponsorMap({ [i18n.language.slice(0, 2)]: t('tree.createName') }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['qb', 'sponsor-maps'] }),
  })
  return (
    <div className="flex flex-col gap-4" data-testid="tree-page">
      <AreaPageHeader title={t('tree.title')} description={t('tree.intro')} />
      {maps.isPending ? <PageState kind="loading" />
        : maps.isError ? <PageState kind="error" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void maps.refetch()}>{tQb('retry')}</Button>} />
        : maps.data.length === 0 ? (
          <PageState kind="empty" title={t('tree.none')} data-testid="tree-none"
            description={create.isError ? problemText(create.error) : undefined}
            action={<div className="flex flex-wrap items-center gap-3">
              <Button type="button" disabled={create.isPending} data-testid="tree-create" onClick={() => create.mutate()}>
                {t('tree.create')}
              </Button>
              <Link to={PUBLICATIONS_PATH} className="text-primary text-sm underline underline-offset-4">
                {t('tree.toPublications')}</Link>
            </div>} />
        )
        : maps.data.map((map) => <OwnMap key={map.treeId} map={map} />)}
    </div>
  )
}

function OwnMap({ map }: { map: SponsorMap }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [editing, setEditing] = useState(false)
  const [shown, setShown] = useState<string | null>(null)
  const versionId = shown ?? map.currentVersionId
  const versions = useQuery({ queryKey: ['qb', 'sponsor-map-versions', map.treeId, map.revision],
    queryFn: () => readSponsorVersions(map.treeId) })
  const version = useQuery({ queryKey: ['qb', 'sponsor-map-version', versionId],
    queryFn: () => readSponsorVersion(map, versionId!), enabled: !!versionId })
  const name = pickText(map.name, i18n.language)
  const open = map.openStatus === 'DRAFT' || map.openStatus === 'REVIEW' || map.openStatus === 'REJECTED'
    ? map.openStatus : null
  return (
    <section className="flex flex-col gap-3" aria-labelledby={`tree-${map.treeId}`} data-testid="tree-map"
      data-tree={map.treeId}>
      <div className="flex flex-wrap items-center gap-2">
        <h2 id={`tree-${map.treeId}`} className="text-lg font-semibold" lang={name.lang}>{name.text}</h2>
        <Badge variant={map.status === 'ACTIVE' ? 'default' : 'outline'} data-testid="tree-status">
          {map.status === 'ACTIVE' || map.status === 'RETIRED' ? t(`tree.status.${map.status}`) : map.status}
        </Badge>
        {!editing && map.status !== 'RETIRED' && (
          <Button type="button" variant="outline" size="sm" data-testid="tree-edit" onClick={() => setEditing(true)}>
            {t('tree.editMap')}
          </Button>
        )}
      </div>
      {editing && <TreeEditor map={map} onClose={() => setEditing(false)} />}
      <p className="text-muted-foreground text-sm" data-testid="tree-version">
        {map.currentVersionNo != null ? t('tree.inEffect', { n: map.currentVersionNo }) : t('tree.noVersion')}
        {map.runningBound > 0 && <> · {t('tree.running', { count: map.runningBound })}</>}
      </p>
      {open && (
        <Alert variant={open === 'REJECTED' ? 'destructive' : 'default'} data-testid="tree-open">
          <AlertDescription>
            {t(`tree.open.${open}`, { n: map.openVersionNo ?? '', reason: map.rejectReason ?? '' })}
          </AlertDescription>
        </Alert>
      )}
      {versionId && (version.isPending ? <PageState kind="loading" />
        : version.isError ? <PageState kind="error" description={tQb('failed')} />
        : version.data ? <KnowledgeMap key={versionId} graph={version.data.graph} litCounts={version.data.litCounts} />
        : null)}
      <details className="text-sm" data-testid="tree-history">
        <summary className="cursor-pointer font-medium">{t('tree.history')}</summary>
        {versions.isSuccess && versions.data.length === 0 && <p className="text-muted-foreground">{t('tree.historyNone')}</p>}
        <ul className="mt-2 flex flex-col gap-1">
          {(versions.data ?? []).map((row) => (
            <li key={row.versionId} className="flex flex-wrap items-center gap-2" data-testid="tree-history-version">
              <span>{t('tree.versionLine', { n: row.versionNo, status: t(`tree.versionStatus.${row.status}`) })}</span>
              {row.rejectReason && <span className="text-muted-foreground">{row.rejectReason}</span>}
              <Button type="button" variant="link" size="sm" className="h-auto p-0"
                aria-pressed={versionId === row.versionId} onClick={() => setShown(row.versionId)}>
                {t('tree.viewVersion')}
              </Button>
            </li>
          ))}
        </ul>
      </details>
    </section>
  )
}
