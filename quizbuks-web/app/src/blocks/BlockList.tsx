import { Button, notify, PageState } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { BLOCKS_QUERY, readBlocks, refreshAfterBlocking, removeBlock, type BlockRow } from './api'

/**
 * Everything the taker blocked (F-S8), newest first, in the app's settings: what each is (a quiz, every round of a
 * quiz, a Sponsor, a video provider) and its name, each unblocked on its own. A blocked quiz links to its page, which
 * says it is blocked.
 */
export function BlockList() {
  const { t, i18n } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const blocks = useQuery({ queryKey: BLOCKS_QUERY, queryFn: readBlocks })
  const unblock = useMutation({
    mutationFn: (blockId: string) => removeBlock(blockId),
    onSuccess: () => {
      notify.success(t('blocks.undone'))
      return refreshAfterBlocking(queryClient)
    },
    onError: (error) => notify.error(problemText(error)),
  })
  const name = (row: BlockRow) => row.name ?? t('blocks.gone')
  const date = (row: BlockRow) => {
    const time = Date.parse(row.createdAt)
    return Number.isFinite(time) ? new Date(time).toLocaleDateString(i18n.language) : ''
  }
  return (
    <div className="flex flex-col gap-3" data-testid="block-list">
      <p className="text-muted-foreground text-sm">{t('blocks.hint')}</p>
      {blocks.isPending ? (
        <PageState kind="loading" />
      ) : blocks.isError ? (
        <PageState kind="error" description={problemText(blocks.error)}
          action={<Button variant="outline" onClick={() => void blocks.refetch()}>{tQb('retry')}</Button>} />
      ) : blocks.data.length === 0 ? (
        <p className="text-muted-foreground text-sm" data-testid="block-list-empty">{t('blocks.empty')}</p>
      ) : (
        <ul className="divide-y" aria-label={t('blocks.title')}>
          {blocks.data.map((row) => (
            <li key={row.blockId} className="flex items-center justify-between gap-3 py-2"
              data-testid="block-list-item" data-kind={row.kind} data-object={row.objectId}>
              <span className="flex min-w-0 flex-col">
                <span className="text-muted-foreground text-xs">{t(`blocks.kinds.${row.kind}`)}</span>
                {row.kind === 'PUBLICATION' && row.publicationId
                  ? <Link to={`/p/${row.publicationId}`} className="truncate underline-offset-4 hover:underline"
                    data-testid="block-list-name">{name(row)}</Link>
                  : <span className="truncate" data-testid="block-list-name">{name(row)}</span>}
                <span className="text-muted-foreground text-xs">{t('blocks.since', { date: date(row) })}</span>
              </span>
              <Button size="sm" variant="outline" disabled={unblock.isPending} data-testid="block-list-unblock"
                aria-label={t('blocks.unblockOf', { name: name(row) })} onClick={() => unblock.mutate(row.blockId)}>
                {t('blocks.unblock')}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
