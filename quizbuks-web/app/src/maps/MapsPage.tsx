import { i18n } from '@jabiz/client'
import { Badge, Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { pickText } from '../qb/legal'
import { QB } from '../qb/messages'
import { useMainlandChina } from '../qb/region'
import { Link } from '../qb/router'
import { readMap, readMyMaps, readStartable, type MapSummary } from '../qb/tree/api'
import { KnowledgeMap, type Startable } from '../qb/tree/KnowledgeMap'
import { statesOf } from '../qb/tree/states'
import { appPublicationPath } from '../qb/page'
import { mapPath, MAPS_PATH } from './paths'
import { AreaHeader } from '../frame/AreaHeader'
import '../frame/messages'

/**
 * "My maps" (F-S3c, docs/quizbuks/plans/impl/F-S3.md §4): the knowledge maps the Taker sees (`qb.app.trees`: the
 * platform's, and a Sponsor's once they answered or lit something in it), each with how many nodes they lit.
 */
export function MapsPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const maps = useQuery({ queryKey: ['qb', 'maps'], queryFn: readMyMaps })
  return (
    <section className="flex flex-col gap-4" aria-labelledby="maps-title" data-testid="maps">
      <div className="flex flex-col gap-1">
        <AreaHeader area="me" id="maps-title" title={t('maps.title')} />
        <p className="text-muted-foreground text-sm">{t('maps.intro')}</p>
      </div>
      {maps.isPending ? <PageState kind="loading" />
        : maps.isError ? <PageState kind="error" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void maps.refetch()}>{tQb('retry')}</Button>} />
        : maps.data.length === 0 ? <PageState kind="empty" title={t('maps.empty')} data-testid="maps-empty" />
        : (
          <ul className="flex flex-col gap-2" data-testid="maps-list">
            {maps.data.map((map) => <MapRow key={map.treeId} map={map} />)}
          </ul>
        )}
    </section>
  )
}

function MapRow({ map }: { map: MapSummary }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const name = pickText(map.name, i18n.language)
  return (
    <li>
      <Link to={mapPath(map.treeId)} className="bg-card hover:bg-accent flex flex-col gap-1 rounded-lg border p-3"
        data-testid="maps-item" data-tree={map.treeId}>
        <span className="flex flex-wrap items-center gap-2">
          <span className="font-medium" lang={name.lang}>{name.text}</span>
          <Badge variant={map.ownerKind === 'PLATFORM' ? 'default' : 'secondary'}>{tQb(`tree.kinds.${map.ownerKind}`)}</Badge>
        </span>
        {map.sponsorCompany && (
          <span className="text-muted-foreground text-sm">{t('maps.by', { company: map.sponsorCompany })}</span>
        )}
        <span className="text-muted-foreground text-sm" data-testid="maps-progress">
          {t('maps.progress', { lit: map.litNodes, nodes: map.nodes })}
        </span>
      </Link>
    </li>
  )
}

/** One map as the Taker sees it: the five states, and the publications they can start from a node. */
export function MapPage({ treeId, country }: { treeId: string; country: string | null }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  return (
    <section className="flex flex-col gap-4" data-testid="map-page">
      <Link to={MAPS_PATH} className="text-primary inline-flex w-fit items-center gap-1 text-sm underline"
        data-testid="map-back">
        <ArrowLeft aria-hidden className="size-4" />{t('maps.back')}
      </Link>
      <PersonalMap treeId={treeId} country={country}
        missing={<PageState kind="notFound" titleAs="h1" description={t('maps.notFound')} />}
        failed={<PageState kind="error" description={tQb('failed')} />} />
    </section>
  )
}

/**
 * A map with the signed-in Taker's states (`qb.app.tree`) and what they can start now (`qb.app.feed` with `tree`);
 * also the Sponsor's page's, signed in.
 */
export function PersonalMap({ treeId, country, missing, failed, heading = 'h1' }: {
  treeId: string
  country: string | null
  missing: ReactNode
  failed: ReactNode
  heading?: 'h1' | 'h3'
}) {
  const [at] = useState(() => new Date().toISOString())
  const region = useMainlandChina()
  const graph = useQuery({ queryKey: ['qb', 'map', treeId], queryFn: () => readMap(treeId) })
  const startable = useQuery({ queryKey: ['qb', 'map-startable', treeId, at, country, region.mainlandChina],
    queryFn: () => readStartable({ treeId, at, country, mainlandChina: region.mainlandChina }),
    enabled: !region.pending })
  if (graph.isPending || (startable.isPending && !region.pending) || region.pending) return <PageState kind="loading" />
  if (graph.isError || startable.isError) return <>{failed}</>
  if (!graph.data) return <>{missing}</>
  const can = startable.data ?? new Map<string, Startable[]>()
  const name = pickText(graph.data.name, i18n.language)
  const Heading = heading
  return (
    <div className="flex flex-col gap-3">
      <Heading className={heading === 'h1' ? 'text-xl font-semibold' : 'font-medium'} lang={name.lang}
        data-testid="map-title">{name.text}</Heading>
      <KnowledgeMap graph={graph.data} states={statesOf(graph.data, new Set(can.keys()))} startable={can}
        publicationLink={(publication) => (
          <Link to={appPublicationPath(publication.publicationId)} className="text-primary underline"
            data-testid="map-publication">{publication.name}</Link>
        )} />
    </div>
  )
}
