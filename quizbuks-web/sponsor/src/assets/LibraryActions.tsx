import {
  Badge,
  Button,
  Command,
  CommandEmpty,
  CommandInput,
  CommandItem,
  CommandList,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, Library } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { MaterialKind } from '../content/api'
import { formProblem, ProblemList } from '../content/editing'
import { SPONSOR } from '../messages'
import type { ContentLanguage } from '../qb/api'
import { fieldProblem } from '../qb/problems'
import {
  ASSETS_QUERY,
  readMyAssets,
  saveAsset,
  useAssetKindLabel,
  useTextUsageLabel,
  type Asset,
  type AssetContent,
  type AssetKind,
  type UseTarget,
} from './api'
import { ASSET_LIMITS } from './ids'
import './messages'
import { suits } from './suits'

/**
 * "Choose from the library" (F-C1): the sponsor's assets that suit the place (`suits`, as the server's
 * `AssetRules.suits`), searchable; the arrow keys move through them and Enter chooses (`Command`). An asset `refused`
 * names a reason is listed but cannot be chosen (a prize card of a method the sponsor may not use).
 */
export function AssetPicker({ open, onOpenChange, target, materialKind = null, materialKinds, refused, onPick }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  target: UseTarget
  /** A material's place: the material's kind (an existing one), or none for a new material of any of `materialKinds`. */
  materialKind?: MaterialKind | null
  /** A new material: the kinds it can be (the asset decides the material's kind). */
  materialKinds?: readonly MaterialKind[]
  refused?: (asset: Asset) => string | null
  onPick: (asset: Asset) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const kindLabel = useAssetKindLabel()
  const usageLabel = useTextUsageLabel()
  const assets = useQuery({ queryKey: ASSETS_QUERY, queryFn: readMyAssets, enabled: open })
  const fitting = (assets.data ?? []).filter((asset) => (materialKinds
    ? materialKinds.some((kind) => suits(target, asset.kind, asset.usage, kind))
    : suits(target, asset.kind, asset.usage, materialKind)))
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg" data-testid="asset-picker" data-target={target}>
        <DialogHeader>
          <DialogTitle>{t('assets.picker.title')}</DialogTitle>
          <DialogDescription>{t('assets.picker.hint')}</DialogDescription>
        </DialogHeader>
        {assets.isPending ? <p className="text-muted-foreground text-sm" role="status">…</p> : (
          <Command className="border" label={t('assets.picker.title')}>
            <CommandInput placeholder={t('assets.picker.search')} aria-label={t('assets.picker.search')}
              data-testid="asset-picker-search" />
            <CommandList className="max-h-72">
              <CommandEmpty data-testid="asset-picker-none">{t('assets.picker.none')}</CommandEmpty>
              {fitting.map((asset) => {
                const reason = refused?.(asset) ?? null
                return (
                  <CommandItem key={asset.assetId} value={asset.assetId}
                    keywords={[asset.name, asset.description ?? '', ...asset.tags]} disabled={reason !== null}
                    data-testid="asset-picker-item" data-id={asset.assetId}
                    onSelect={() => {
                      onPick(asset)
                      onOpenChange(false)
                      notify.success(t('assets.picker.chosen', { name: asset.name }))
                    }}>
                    <span className="flex min-w-0 flex-col">
                      <span className="truncate font-medium">{asset.name}</span>
                      <span className="text-muted-foreground text-xs">
                        {asset.usage ? t('assets.kindWithUsage', { kind: kindLabel(asset.kind), usage: usageLabel(asset.usage) })
                          : kindLabel(asset.kind)}
                        {reason && <> · {reason}</>}
                      </span>
                    </span>
                    {asset.tags.length > 0 && (
                      <span className="ms-auto flex flex-wrap gap-1">
                        {asset.tags.slice(0, 3).map((tag) => <Badge key={tag} variant="outline">{tag}</Badge>)}
                      </span>
                    )}
                  </CommandItem>
                )
              })}
            </CommandList>
          </Command>
        )}
      </DialogContent>
    </Dialog>
  )
}

/** What "Save to the library" makes from a place: the asset's kind and content, its suggested name and language. */
export interface StoredContent {
  kind: AssetKind
  name: string
  language: ContentLanguage
  description?: string | null
  content: Partial<AssetContent>
}

/**
 * The two buttons beside a place that the library serves (F-C1): "Choose from the library" and "Save to the library".
 * Saving makes a new asset of what the place has now (`QB_ASSET_SAVE`, named in a small dialog); `onStored` then lets
 * the place count as that asset's copy, so that its next save records where the asset is used.
 */
export function LibraryActions({ target, materialKind, materialKinds, refused, onPick, store, onStored, disabled,
  testId, name }: {
  target: UseTarget
  materialKind?: MaterialKind | null
  materialKinds?: readonly MaterialKind[]
  refused?: (asset: Asset) => string | null
  onPick: (asset: Asset) => void
  /** What the place has now as an asset; none while it has nothing to save (the button is then not offered). */
  store?: StoredContent | null
  onStored?: (assetId: string, stored: StoredContent) => void
  disabled?: boolean
  testId: string
  /** What the place is ("Option 2 image"), for the buttons' names where several places are side by side. */
  name?: string
}) {
  const { t } = useTranslation(SPONSOR)
  const [picking, setPicking] = useState(false)
  const [storing, setStoring] = useState(false)
  return (
    <div className="flex flex-wrap gap-2" data-testid={testId}>
      <Button type="button" variant="outline" size="sm" disabled={disabled} data-testid={`${testId}-pick`}
        aria-label={name ? t('assets.picker.openFor', { name }) : undefined}
        onClick={() => setPicking(true)}>
        <Library aria-hidden />
        {t('assets.picker.open')}
      </Button>
      {store !== undefined && (
        <Button type="button" variant="ghost" size="sm" disabled={disabled || !store} data-testid={`${testId}-store`}
          aria-label={name ? t('assets.store.openFor', { name }) : undefined}
          onClick={() => setStoring(true)}>
          <Archive aria-hidden />
          {t('assets.store.open')}
        </Button>
      )}
      {picking && <AssetPicker open onOpenChange={setPicking} target={target} materialKind={materialKind}
        materialKinds={materialKinds} refused={refused} onPick={onPick} />}
      {storing && store && <StoreDialog stored={store} onClose={() => setStoring(false)}
        onStored={(assetId) => {
          setStoring(false)
          onStored?.(assetId, store)
        }} />}
    </div>
  )
}

/** "Save to the library": the name to keep it by, then a new asset (`QB_ASSET_SAVE`); every problem at once. */
function StoreDialog({ stored, onClose, onStored }: {
  stored: StoredContent
  onClose: () => void
  onStored: (assetId: string) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [name, setName] = useState(stored.name.slice(0, ASSET_LIMITS.name))
  const save = useMutation({
    mutationFn: () => saveAsset({ assetId: null, kind: stored.kind, name: name.trim(),
      description: stored.description?.trim() ? stored.description.trim().slice(0, ASSET_LIMITS.description) : null,
      language: stored.language, tags: [], baseRevision: null, ...stored.content }),
    onSuccess: async (output) => {
      notify.success(t('assets.store.stored', { name: name.trim() }))
      onStored(output.assetId)
      await queryClient.invalidateQueries({ queryKey: ASSETS_QUERY })
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    event.stopPropagation()
    save.mutate()
  }
  const problem = fieldProblem(save.error, 'name')
  return (
    <Dialog open onOpenChange={(open) => !open && !save.isPending && onClose()}>
      <DialogContent className="sm:max-w-md" data-testid="asset-store-dialog">
        <DialogHeader>
          <DialogTitle>{t('assets.store.title')}</DialogTitle>
          <DialogDescription>{t('assets.store.hint')}</DialogDescription>
        </DialogHeader>
        <form id={`${id}-form`} noValidate onSubmit={submit} className="flex flex-col gap-3">
          <Label htmlFor={`${id}-name`}>{t('assets.store.name')}</Label>
          <Input id={`${id}-name`} value={name} maxLength={ASSET_LIMITS.name} autoComplete="off" required
            aria-invalid={problem ? true : undefined} aria-describedby={problem ? `${id}-error` : undefined}
            data-testid="asset-store-name" onChange={(event) => setName(event.target.value)} />
          {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
          <ProblemList problems={formProblem(save.error, ['name'])} testId="asset-store-error" />
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" disabled={save.isPending} onClick={onClose}>{t('assets.cancel')}</Button>
          <Button type="submit" form={`${id}-form`} disabled={save.isPending || name.trim() === ''}
            aria-busy={save.isPending || undefined} data-testid="asset-store-save">{t('assets.store.save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
