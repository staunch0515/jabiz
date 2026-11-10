import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  type ColumnDef,
  type SortingState,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FolderInput, Plus } from 'lucide-react'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { formProblem, ProblemList } from '../content/editing'
import { quizPath } from '../content/paths'
import { EmptyState } from '../EmptyState'
import { SPONSOR } from '../messages'
import { ProductsPage } from '../products/ProductsPage'
import { publicationPath } from '../publishing/paths'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import {
  ASSET_TABS,
  isAssetTab,
  ASSETS_PATH,
  ASSETS_QUERY,
  assetUsesQuery,
  collectAssets,
  deleteAsset,
  matches,
  readAssetUses,
  readCandidates,
  readMyAssets,
  TAB_KINDS,
  useAssetKindLabel,
  useTextUsageLabel,
  useUseTargetLabel,
  type Asset,
  type AssetKind,
  type AssetTab,
  type Candidate,
  type CollectOutput,
} from './api'
import { AssetDialog } from './AssetDialog'
import { latestShares, MY_SHARED_QUERY, readMySharedVideos, standing } from '../sharedvideos/api'
import { MySharedVideos, SharedStatus, ShareVideoDialog } from '../sharedvideos/Sharing'
import { VideoUsesProvided } from '../videouses/VideoUsesProvided'
import { ASSET_LIMITS } from './ids'
import './messages'
import { UsageCard } from './UsageCard'
import { AreaPageHeader } from '../shell/AreaPageHeader'
import { mainAction } from '../shell/mainAction'
import { ASSET_STATS_QUERY, formatBytes, readAssetStats, sizeOf, USAGE_QUERY } from './usage'

const ALL = '*'

/** The tab's address (F-F3: tabs of the area, `/assets/<tab>`; the prizes' tab is `/assets/prizes`). */
export const assetTabPath = (tab: AssetTab) => tab === 'all' ? ASSETS_PATH
  : `${ASSETS_PATH}/${tab === 'prize' ? 'prizes' : tab}`

/** The tab of a path of the library; null for a path that is no tab of it. */
export function tabOf(path: string): AssetTab | null {
  return ASSET_TABS.find((tab) => assetTabPath(tab) === path.replace(/\/+$/, '') ) ?? null
}

/**
 * The sponsor's asset library (F-C1, docs/quizbuks/plans/impl/F-C1.md F-C1c): every asset of theirs in tabs by what
 * it is (all, media, texts, prizes), searched by name, description or tag and filtered by kind and tag on the page
 * (the library is bounded by `qb.assets.max-count`; tags are stored as JSON text, which the server cannot filter).
 * Each asset is edited, deleted (after confirming) or looked up where it was copied to ("older content" marked);
 * "Collect" takes what the sponsor's quizzes and publications already have.
 *
 * F-C2: the usage card on top (the latest daily snapshot against the free amount, a banner above it), each asset's
 * size (its file as uploaded and its texts, `qb.sponsor.asset-stats`) to sort by, and "only the unused assets" (copied
 * nowhere) to clean up with.
 *
 * F-C3: a video link's row shows how its latest share stands (`qb.sponsor.my-shared-videos`); "Share" is offered while
 * no share stands (never shared, or the last one sent back, withdrawn or taken down).
 */
export function AssetsPage({ tab, adding = false }: { tab: AssetTab; adding?: boolean }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const queryClient = useQueryClient()
  const kindLabel = useAssetKindLabel()
  const assets = useQuery({ queryKey: ASSETS_QUERY, queryFn: readMyAssets, enabled: isAssetTab(tab) })
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState<string>(ALL)
  const [tag, setTag] = useState<string>(ALL)
  const [unusedOnly, setUnusedOnly] = useState(false)
  const [sorting, setSorting] = useState<SortingState>([])
  const stats = useQuery({ queryKey: ASSET_STATS_QUERY, queryFn: readAssetStats, enabled: isAssetTab(tab) })
  // The global "+ New" opens the library with the new asset's dialog (`?new=1`, F-F3).
  const [editing, setEditing] = useState<Asset | 'new' | null>(adding && isAssetTab(tab) ? 'new' : null)
  const [usesOf, setUsesOf] = useState<Asset | null>(null)
  const [collecting, setCollecting] = useState(false)
  // F-S12: the video link being shared with other Sponsors.
  const [sharing, setSharing] = useState<Asset | null>(null)
  const shares = useQuery({ queryKey: MY_SHARED_QUERY, queryFn: readMySharedVideos, enabled: isAssetTab(tab) })
  const latest = useMemo(() => latestShares(shares.data ?? []), [shares.data])
  const refresh = () => Promise.all([ASSETS_QUERY, ASSET_STATS_QUERY, USAGE_QUERY]
    .map((queryKey) => queryClient.invalidateQueries({ queryKey }))).then(() => undefined)
  const remove = useMutation({
    mutationFn: (asset: Asset) => deleteAsset(asset.assetId, asset.revision),
    onSuccess: async () => {
      notify.success(t('assets.deleted'))
      await refresh()
    },
    onError: (error) => notify.error(problemText(error)),
  })

  // Stable for the columns: cells built anew on each render would be mounted anew, losing what is open in them.
  const removeAsset = remove.mutate
  const removing = remove.isPending

  const kinds = TAB_KINDS[tab]
  const inTab = (assets.data ?? []).filter((asset) => kinds.includes(asset.kind))
  const tags = [...new Set(inTab.flatMap((asset) => asset.tags))].sort((a, b) => a.localeCompare(b))
  const shownKind = kind !== ALL && kinds.includes(kind as AssetKind) ? kind : ALL
  const shownTag = tag !== ALL && tags.includes(tag) ? tag : ALL
  const statsOf = stats.data
  const filtered = inTab.filter((asset) => (shownKind === ALL || asset.kind === shownKind)
    && (shownTag === ALL || asset.tags.includes(shownTag)) && matches(asset, search)
    && (!unusedOnly || (statsOf?.get(asset.assetId)?.uses ?? 0) === 0))
  // Sorted here: the page holds the whole library (at most qb.assets.max-count).
  const bySize = sorting.find((sort) => sort.id === 'size')
  const rows = bySize ? [...filtered].sort((a, b) => (sizeOf(statsOf?.get(a.assetId)) - sizeOf(statsOf?.get(b.assetId)))
    * (bySize.desc ? -1 : 1)) : filtered
  const showUnused = () => {
    setUnusedOnly(true)
    if (!isAssetTab(tab)) navigate(ASSETS_PATH, { replace: true })
  }

  const columns = useMemo<ColumnDef<Asset, unknown>[]>(() => [
    { id: 'name', header: t('assets.columns.name'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => <span className="font-medium" data-testid="asset-name-cell">{row.original.name}</span> },
    { id: 'kind', header: t('assets.columns.kind'), enableSorting: false, meta: { card: 'subtitle' },
      cell: ({ row }) => <KindOf asset={row.original} /> },
    { id: 'tags', header: t('assets.columns.tags'), enableSorting: false, meta: { card: 'badge' },
      cell: ({ row }) => row.original.tags.length > 0 && (
        <span className="flex flex-wrap gap-1">
          {row.original.tags.map((value) => <Badge key={value} variant="outline">{value}</Badge>)}
        </span>
      ) },
    { id: 'sharing', header: t('sharedVideos.column'), enableSorting: false,
      cell: ({ row }) => {
        if (row.original.kind !== 'VIDEO_LINK') return ''
        const share = latest.get(row.original.assetId)
        return share ? <SharedStatus status={share.status} />
          : <span className="text-muted-foreground text-sm" data-testid="shared-video-status" data-status="NONE">
            {t('sharedVideos.columnNone')}</span>
      } },
    { id: 'edited', header: t('assets.columns.edited'), enableSorting: false,
      cell: ({ row }) => (row.original.editedAt ? formatDateTime(row.original.editedAt) : '') },
    { id: 'size', header: t('assets.columns.size'), enableSorting: true, sortDescFirst: true,
      accessorFn: (asset) => sizeOf(statsOf?.get(asset.assetId)),
      cell: ({ row }) => (statsOf ? <span data-testid="asset-size">{formatBytes(sizeOf(statsOf.get(row.original.assetId)))}</span>
        : '') },
    { id: 'actions', header: t('assets.columns.actions'), enableSorting: false, meta: { card: 'actions' },
      cell: ({ row }) => (
        <span className="flex flex-wrap gap-2">
          <Button variant="outline" size="sm" data-testid="asset-edit"
            aria-label={t('assets.editNamed', { name: row.original.name })} onClick={() => setEditing(row.original)}>
            {t('assets.edit')}
          </Button>
          <Button variant="ghost" size="sm" data-testid="asset-uses"
            aria-label={t('assets.uses.openNamed', { name: row.original.name })} onClick={() => setUsesOf(row.original)}>
            {t('assets.uses.open')}
          </Button>
          {/* F-S12: a video link with its duration may be shared with other Sponsors (F-C3: while no share of it
              stands; again after one was sent back, withdrawn or taken down; the server refuses a second one anyway). */}
          {row.original.kind === 'VIDEO_LINK' && !standing(latest.get(row.original.assetId)?.status ?? 'WITHDRAWN') && (
            <Button variant="ghost" size="sm" data-testid="asset-share"
              aria-label={t('sharedVideos.shareNamed', { name: row.original.name })}
              onClick={() => setSharing(row.original)}>
              {t('sharedVideos.share')}
            </Button>
          )}
          <ConfirmDialog destructive title={t('assets.deleteTitle')} description={t('assets.deleteText')}
            confirmLabel={t('assets.delete')} cancelLabel={t('assets.cancel')}
            onConfirm={() => removeAsset(row.original)}
            trigger={<Button variant="ghost" size="sm" disabled={removing} data-testid="asset-delete"
              aria-label={t('assets.deleteNamed', { name: row.original.name })}>{t('assets.delete')}</Button>} />
        </span>
      ) },
  ], [t, removeAsset, removing, statsOf, latest])

  return (
    <section className="flex flex-col gap-4" data-testid="assets-page">
      <AreaPageHeader title={t('assets.title')} description={t('assets.intro')}
        primaryAction={isAssetTab(tab) ? mainAction({ label: t('assets.add'), icon: Plus, testId: 'asset-add',
          onClick: () => setEditing('new') }) : undefined}
        actions={isAssetTab(tab) && (
          <Button variant="outline" data-testid="asset-collect" onClick={() => setCollecting(true)}>
            <FolderInput aria-hidden />{t('assets.collect')}
          </Button>
        )} />
      <UsageCard onShowUnused={showUnused} />
      {tab === 'products' && <ProductsPage />}
      {isAssetTab(tab) && <div className="flex flex-col gap-4" data-testid={`assets-tab-content-${tab}`}>
          <div className="flex flex-wrap items-end gap-3" role="search" aria-label={t('assets.search')}>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-search`}>{t('assets.search')}</Label>
              <Input id={`${id}-search`} type="search" value={search} className="w-64" autoComplete="off"
                placeholder={t('assets.searchPlaceholder')} data-testid="assets-search"
                onChange={(event) => setSearch(event.target.value)} />
            </div>
            {kinds.length > 1 && (
              <div className="flex flex-col gap-1">
                <Label htmlFor={`${id}-kind`}>{t('assets.kindFilter')}</Label>
                <Select value={shownKind} onValueChange={setKind}>
                  <SelectTrigger id={`${id}-kind`} className="w-44" data-testid="assets-kind"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ALL}>{t('assets.allKinds')}</SelectItem>
                    {kinds.map((code) => <SelectItem key={code} value={code}>{kindLabel(code)}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            )}
            <div className="flex items-center gap-2 self-center pt-5">
              <Checkbox id={`${id}-unused`} checked={unusedOnly} data-testid="assets-unused"
                onCheckedChange={(checked) => setUnusedOnly(checked === true)} />
              <Label htmlFor={`${id}-unused`}>{t('assets.unusedFilter')}</Label>
            </div>
            {tags.length > 0 && (
              <div className="flex flex-col gap-1">
                <Label htmlFor={`${id}-tag`}>{t('assets.tagFilter')}</Label>
                <Select value={shownTag} onValueChange={setTag}>
                  <SelectTrigger id={`${id}-tag`} className="w-44" data-testid="assets-tag"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ALL}>{t('assets.allTags')}</SelectItem>
                    {tags.map((value) => <SelectItem key={value} value={value}>{value}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            )}
          </div>
          {assets.isPending && <PageState kind="loading" />}
          {assets.isError && <PageState kind="error" description={problemText(assets.error)}
            action={<Button variant="outline" onClick={() => void assets.refetch()}>{tQb('retry')}</Button>} />}
          {assets.data && (
            <>
              <p className="text-muted-foreground text-sm" data-testid="assets-count">
                {t('assets.count', { count: assets.data.length })}
              </p>
              <DataTable label={t('assets.title')} columns={columns} data={rows} storageKey="qb.sponsor.assets"
                getRowId={(row) => row.assetId} sorting={sorting} onSortingChange={setSorting}
                empty={<EmptyState testId={assets.data.length === 0 ? 'assets-empty' : 'assets-none'}>
                  {assets.data.length === 0 ? t('assets.empty') : t('assets.emptyFiltered')}
                </EmptyState>}
                rowProps={(row) => ({ 'data-testid': 'asset-row', 'data-id': row.assetId, 'data-kind': row.kind })} />
            </>
          )}
        </div>}
      {editing && (
        <AssetDialog key={editing === 'new' ? 'new' : editing.assetId} asset={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null)
            notify.success(t('assets.saved'))
            await refresh()
          }} />
      )}
      {usesOf && <UsesDialog asset={usesOf} onClose={() => setUsesOf(null)} />}
      {sharing && <ShareVideoDialog asset={sharing} onClose={() => setSharing(null)} onShared={() => setSharing(null)} />}
      {/* F-S12: the videos the Sponsor shares, with the review's result, on the tabs that hold video links. */}
      {(tab === 'all' || tab === 'media') && <MySharedVideos />}
      {/* F-S14: the uses of those videos: the ledger entries to confirm and the figures. */}
      {(tab === 'all' || tab === 'media') && <VideoUsesProvided />}
      {collecting && <CollectDialog onClose={() => setCollecting(false)} onCollected={refresh} />}
    </section>
  )
}

/** An asset's kind, and a text's use. */
export function KindOf({ asset }: { asset: Pick<Asset, 'kind' | 'usage'> }) {
  const { t } = useTranslation(SPONSOR)
  const kindLabel = useAssetKindLabel()
  const usageLabel = useTextUsageLabel()
  return asset.usage ? t('assets.kindWithUsage', { kind: kindLabel(asset.kind), usage: usageLabel(asset.usage) })
    : kindLabel(asset.kind)
}

/** Where an asset was copied to (`qb.sponsor.asset-uses`), each place linked, those copied before its change marked. */
function UsesDialog({ asset, onClose }: { asset: Asset; onClose: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const targetLabel = useUseTargetLabel()
  const uses = useQuery({ queryKey: assetUsesQuery(asset.assetId), queryFn: () => readAssetUses(asset.assetId) })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg" data-testid="asset-uses-dialog">
        <DialogHeader>
          <DialogTitle>{t('assets.uses.title', { name: asset.name })}</DialogTitle>
          <DialogDescription>{t('assets.uses.hint')}</DialogDescription>
        </DialogHeader>
        {uses.isPending && <PageState kind="loading" />}
        {uses.isError && <p className="text-destructive text-sm" role="alert">{problemText(uses.error)}</p>}
        {uses.data && (uses.data.length === 0
          ? <p className="text-muted-foreground text-sm" data-testid="asset-uses-none">{t('assets.uses.none')}</p>
          : (
            <ul className="flex flex-col gap-2">
              {uses.data.map((use) => {
                const to = use.publicationId ? publicationPath(use.publicationId) : use.quizId ? quizPath(use.quizId) : null
                const name = use.targetName ?? t('assets.uses.removedQuiz')
                return (
                  <li key={use.useId} className="flex flex-wrap items-center gap-2 rounded-md border p-2"
                    data-testid="asset-use" data-stale={String(use.stale)}>
                    <span className="flex min-w-0 flex-1 flex-col">
                      {to ? <Link to={to} className="text-primary truncate underline underline-offset-4"
                        onNavigate={onClose}>{name}</Link> : <span className="truncate">{name}</span>}
                      <span className="text-muted-foreground text-xs">{targetLabel(use.targetKind)}</span>
                    </span>
                    {use.stale
                      ? <Badge variant="warning" data-testid="asset-use-stale">{t('assets.uses.stale')}</Badge>
                      : <Badge variant="outline">{t('assets.uses.current')}</Badge>}
                  </li>
                )
              })}
            </ul>
          ))}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('assets.uses.close')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/**
 * "Collect from my content" (F-C1): the candidates of `qb.sponsor.asset-candidates` (read when opened, once each),
 * ticked one by one or all, collected with `QB_ASSET_COLLECT`; the counts of those added and of those skipped as
 * already in the library follow.
 */
function CollectDialog({ onClose, onCollected }: { onClose: () => void; onCollected: () => Promise<void> }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const kindLabel = useAssetKindLabel()
  const targetLabel = useUseTargetLabel()
  const candidates = useQuery({ queryKey: ['qb', 'asset-candidates'], queryFn: readCandidates, gcTime: 0 })
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const [done, setDone] = useState<CollectOutput | null>(null)
  const list = candidates.data ?? []
  const collect = useMutation({
    mutationFn: () => collectAssets(list.filter((c) => chosen.has(c.candidateKey)),
      (candidate: Candidate) => targetLabel(candidate.sourceKind)),
    // The refresh is not awaited: the mutation would stay pending while the library is read again, and a dialog
    // that already says what was added would ignore Escape and Close until then.
    onSuccess: (output) => {
      setDone(output)
      setChosen(new Set())
      void onCollected()
    },
  })
  const all = list.length > 0 && chosen.size === list.length
  return (
    <Dialog open onOpenChange={(open) => !open && !collect.isPending && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl" data-testid="asset-collect-dialog">
        <DialogHeader>
          <DialogTitle>{t('assets.collectDialog.title')}</DialogTitle>
          <DialogDescription>{t('assets.collectDialog.intro')}</DialogDescription>
        </DialogHeader>
        {done && (
          <p role="status" className="text-success text-sm" data-testid="asset-collect-done">
            {t('assets.collectDialog.done', { created: done.created.length, skipped: done.skipped.length })}
          </p>
        )}
        {candidates.isPending && <PageState kind="loading" />}
        {candidates.isError && <p className="text-destructive text-sm" role="alert">{problemText(candidates.error)}</p>}
        {candidates.data && !done && (list.length === 0
          ? <p className="text-muted-foreground text-sm" data-testid="asset-collect-none">{t('assets.collectDialog.none')}</p>
          : (
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center gap-3">
                <Checkbox id={`${id}-all`} checked={all ? true : chosen.size > 0 ? 'indeterminate' : false}
                  data-testid="asset-collect-all"
                  onCheckedChange={(checked) => setChosen(checked === true
                    ? new Set(list.map((c) => c.candidateKey)) : new Set())} />
                <Label htmlFor={`${id}-all`}>{t('assets.collectDialog.selectAll')}</Label>
                <span className="text-muted-foreground text-sm" aria-live="polite">
                  {t('assets.collectDialog.selected', { count: chosen.size })}
                </span>
              </div>
              {list.length >= ASSET_LIMITS.maxCollect && (
                <p className="text-muted-foreground text-sm">{t('assets.collectDialog.more', { max: ASSET_LIMITS.maxCollect })}</p>
              )}
              <ul className="flex flex-col gap-2">
                {list.map((candidate) => {
                  const key = `${id}-${candidate.candidateKey}`
                  return (
                    <li key={candidate.candidateKey} className="flex items-start gap-2 rounded-md border p-2"
                      data-testid="asset-candidate">
                      <Checkbox id={key} checked={chosen.has(candidate.candidateKey)} className="mt-0.5"
                        data-testid="asset-candidate-check"
                        onCheckedChange={(checked) => setChosen((current) => {
                          const next = new Set(current)
                          if (checked === true) next.add(candidate.candidateKey)
                          else next.delete(candidate.candidateKey)
                          return next
                        })} />
                      <Label htmlFor={key} className="flex min-w-0 flex-col items-start gap-0.5 font-normal">
                        <span className="font-medium">{candidate.name ?? targetLabel(candidate.sourceKind)}</span>
                        <span className="text-muted-foreground text-xs">
                          {kindLabel(candidate.kind as AssetKind)} · {t('assets.collectDialog.from', { place: targetLabel(candidate.sourceKind) })}
                        </span>
                        {candidate.body && <span className="text-muted-foreground line-clamp-2 text-xs">{candidate.body}</span>}
                        {candidate.url && <span className="text-muted-foreground text-xs break-all">{candidate.url}</span>}
                      </Label>
                    </li>
                  )
                })}
              </ul>
            </div>
          ))}
        <ProblemList problems={formProblem(collect.error, [])} testId="asset-collect-error" />
        <DialogFooter>
          <Button type="button" variant="outline" disabled={collect.isPending} onClick={onClose}>
            {done ? t('assets.uses.close') : t('assets.cancel')}
          </Button>
          {!done && (
            <Button type="button" disabled={chosen.size === 0 || collect.isPending} aria-busy={collect.isPending || undefined}
              data-testid="asset-collect-run" onClick={() => collect.mutate()}>
              {t('assets.collectDialog.run')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
