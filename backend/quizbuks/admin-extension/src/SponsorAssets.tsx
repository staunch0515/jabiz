import { useQuery } from '@tanstack/react-query'
import { createContext, useContext, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Badge,
  Button,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import {
  ASSET_KINDS,
  countAssetUses,
  PUBLICATION_TARGETS,
  QUIZ_TARGETS,
  readAssetUses,
  readSponsorAssets,
  type AssetRow,
  type AssetUseRow,
} from './asset-api'
import { EmptyState, LoadFailed, Section } from './components'
import { useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const ALL = 'ALL'
/** The columns sorted on the server, by the entity's list view. */
const SORTS = { name: 'name', editedAt: 'editedAt' } as const

/**
 * The places one asset was copied to (F-C1d), read when opened: a copy made before the asset's current revision is
 * marked as older content (the Sponsor's page says the same). A quiz's place links to the quiz, a publication's to the
 * publication; a material's, question's or option's shows its id (the datasets do not say whose quiz it is).
 */
function AssetUses({ asset, onClose }: { asset: AssetRow; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const target = useLabels(DICTIONARY.assetUseTarget)
  const { query, table } = usePaged(['qb-ext', 'asset-uses', asset.assetId],
    (offset, limit) => readAssetUses(asset.assetId, offset, limit))
  const columns = useMemo<ColumnDef<AssetUseRow, unknown>[]>(() => {
    const place = (use: AssetUseRow) => {
      if (QUIZ_TARGETS.has(use.targetKind) && can(PERMISSION.quizRead)) return PATH.quiz(use.targetId)
      if (PUBLICATION_TARGETS.has(use.targetKind) && can(PERMISSION.publicationRead)) return PATH.publication(use.targetId)
      return null
    }
    return [
      { id: 'place', header: t('assets.uses.place'), cell: ({ row }) => target(row.original.targetKind) },
      {
        id: 'id',
        header: t('assets.uses.id'),
        cell: ({ row }) => {
          const to = place(row.original)
          return to
            ? <Link to={to} className={`${linkClass} font-mono text-xs break-all`} data-testid="asset-use-link">
              {row.original.targetId}</Link>
            : <span className="font-mono text-xs break-all">{row.original.targetId}</span>
        },
      },
      { id: 'revision', header: t('assets.uses.revision'), meta: right, cell: ({ row }) => String(row.original.assetRevision) },
      { id: 'usedAt', header: t('assets.uses.usedAt'), cell: ({ row }) => when(row.original.usedAt) },
      {
        id: 'state',
        header: t('assets.uses.state'),
        cell: ({ row }) => (row.original.assetRevision < asset.revision
          ? <Badge variant="warning" data-testid="sponsor-asset-use-stale">{t('assets.uses.stale')}</Badge>
          : <span className="text-muted-foreground text-xs">{t('assets.uses.current')}</span>),
      },
    ]
  }, [t, can, target, asset.revision])
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-3xl" data-testid="sponsor-asset-uses">
        <DialogHeader>
          <DialogTitle>{t('assets.uses.title', { name: asset.name })}</DialogTitle>
          <DialogDescription>{t('assets.uses.hint')}</DialogDescription>
        </DialogHeader>
        {query.isError ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} /> : (
          <DataTable label={t('assets.uses.table')} columns={columns} {...table} getRowId={(row) => row.useId}
            empty={<EmptyState art="content" title={t('assets.uses.none')} />}
            rowProps={(row) => ({ 'data-testid': 'sponsor-asset-use', 'data-kind': row.targetKind })} />
        )}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('assets.uses.close')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** The counts of the page's uses, given to the cells through a context so that the rows are not drawn again. */
const UseCounts = createContext<{ counts: Map<string, number>; complete: boolean } | undefined>(undefined)

function UseCount({ assetId }: { assetId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const uses = useContext(UseCounts)
  return (
    <span className="tabular-nums" data-testid="sponsor-asset-use-count">
      {uses ? t(uses.complete ? 'assets.usesCount' : 'assets.usesAtLeast', { count: uses.counts.get(assetId) ?? 0 }) : ''}
    </span>
  )
}

/**
 * A Sponsor's asset library, read only (F-C1, docs/quizbuks/plans/impl/F-C1.md F-C1d): for support to see what the
 * Sponsor keeps and where each asset was copied to, when a Sponsor says an asset is missing or a place shows older
 * content. Shown to readers of every publication (`qb.admin.publication.read`: support, and an operator's members,
 * whom the server limits to their own Sponsors); there is nothing to change here: only the Sponsor changes, deletes
 * or collects their assets.
 */
export function SponsorAssets({ userId }: { userId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const kindLabel = useLabels(DICTIONARY.assetKind)
  const usageLabel = useLabels(DICTIONARY.assetTextUsage)
  const [kind, setKind] = useState<string>(ALL)
  const [open, setOpen] = useState<AssetRow | null>(null)
  const chosenKind = kind === ALL ? null : kind
  const { query, table, sortable } = usePaged(['qb-ext', 'sponsor-assets', userId, chosenKind],
    (offset, limit, sorts) => readSponsorAssets(userId, offset, limit, sorts, chosenKind), true, SORTS)
  const ids = (query.data?.items ?? []).map((asset) => asset.assetId)
  const uses = useQuery({
    queryKey: ['qb-ext', 'sponsor-asset-use-counts', ids],
    queryFn: () => countAssetUses(ids),
    enabled: ids.length > 0,
  })
  const columns = useMemo<ColumnDef<AssetRow, unknown>[]>(() => [
    {
      id: 'name',
      header: t('assets.columns.name'),
      cell: ({ row }) => (
        <span className="flex flex-col">
          <span className="font-medium">{row.original.name}</span>
          {row.original.description && (
            <span className="text-muted-foreground line-clamp-2 text-xs">{row.original.description}</span>
          )}
        </span>
      ),
    },
    {
      id: 'kind',
      header: t('assets.columns.kind'),
      cell: ({ row }) => (row.original.usage
        ? t('assets.kindWithUsage', { kind: kindLabel(row.original.kind), usage: usageLabel(row.original.usage) })
        : kindLabel(row.original.kind)),
    },
    {
      id: 'tags',
      header: t('assets.columns.tags'),
      cell: ({ row }) => (
        <span className="flex flex-wrap gap-1" data-testid="sponsor-asset-tags">
          {row.original.tags.map((tag) => <Badge key={tag} variant="outline">{tag}</Badge>)}
        </span>
      ),
    },
    { id: 'revision', header: t('assets.columns.revision'), meta: right, cell: ({ row }) => String(row.original.revision) },
    { id: 'editedAt', header: t('assets.columns.editedAt'), cell: ({ row }) => when(row.original.editedAt) },
    {
      id: 'uses',
      header: t('assets.columns.uses'),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          <UseCount assetId={row.original.assetId} />
          <Button type="button" variant="link" size="sm" className="h-auto p-0" data-testid="sponsor-asset-uses-open"
              aria-label={t('assets.usesOpenNamed', { name: row.original.name })} onClick={() => setOpen(row.original)}>
            {t('assets.usesOpen')}
          </Button>
        </span>
      ),
    },
  ], [t, kindLabel, usageLabel])
  return (
    <Section title={t('assets.title')} testId="sponsor-assets"
      actions={<Link to={`${PATH.usage}?${new URLSearchParams({ sponsor: userId })}`} className={linkClass}
        aria-label={t('usage.sponsorLinkNamed')} data-testid="sponsor-assets-usage">{t('usage.sponsorLink')}</Link>}>
      <p className="text-muted-foreground text-sm">{t('assets.hint')}</p>
      <div className="flex items-center gap-2">
        <Label htmlFor="sponsor-assets-kind">{t('assets.kind')}</Label>
        <Select value={kind} onValueChange={setKind}>
          <SelectTrigger id="sponsor-assets-kind" className="w-48" data-testid="sponsor-assets-kind">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>{t('assets.allKinds')}</SelectItem>
            {ASSET_KINDS.map((code) => <SelectItem key={code} value={code}>{kindLabel(code)}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      {query.isError ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} /> : (
        <UseCounts.Provider value={uses.data}>
          <DataTable label={t('assets.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.assetId}
            empty={<EmptyState art="content" title={t('assets.none')} />}
            rowProps={(row) => ({ 'data-testid': 'sponsor-asset-row', 'data-id': row.assetId, 'data-kind': row.kind })} />
        </UseCounts.Provider>
      )}
      {open && <AssetUses asset={open} onClose={() => setOpen(null)} />}
    </Section>
  )
}
