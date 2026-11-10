import { i18n } from '@jabiz/client'
import { Alert, AlertDescription, Badge, Button, PageHeader, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { pickText } from '../qb/legal'
import { QB } from '../qb/messages'
import { Link } from '../qb/router'
import { readSponsorMaps, readSponsorVersion, type SponsorMap } from '../qb/tree/api'
import { KnowledgeMap } from '../qb/tree/KnowledgeMap'
import { PUBLICATIONS_PATH } from '../publishing/paths'
import './messages'

/**
 * The Sponsor's knowledge map (F-S3c, docs/quizbuks/plans/impl/F-S3.md §4; Q85's tree page before, at the same
 * address): each own map's version in effect drawn by the shared component with how many Takers lit each node (under
 * the minimum group, none: `qb.sponsor.tree`), and the open version's state with the reviewer's reason. Read only:
 * the editor is F-S3d.
 */
export function TreePage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const maps = useQuery({ queryKey: ['qb', 'sponsor-maps'], queryFn: readSponsorMaps })
  return (
    <div className="flex flex-col gap-4" data-testid="tree-page">
      <PageHeader title={t('tree.title')} description={t('tree.intro')} />
      {maps.isPending ? <PageState kind="loading" />
        : maps.isError ? <PageState kind="error" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void maps.refetch()}>{tQb('retry')}</Button>} />
        : maps.data.length === 0 ? (
          <PageState kind="empty" title={t('tree.none')} data-testid="tree-none"
            action={<Link to={PUBLICATIONS_PATH} className="text-primary text-sm underline underline-offset-4">
              {t('tree.toPublications')}</Link>} />
        )
        : maps.data.map((map) => <OwnMap key={map.treeId} map={map} />)}
    </div>
  )
}

function OwnMap({ map }: { map: SponsorMap }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const versionId = map.currentVersionId
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
      </div>
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
        : version.data ? <KnowledgeMap graph={version.data.graph} litCounts={version.data.litCounts} />
        : null)}
    </section>
  )
}
