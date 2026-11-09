import { Alert, AlertDescription, AlertTitle, Button, notify } from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Ban } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import type { PublicationBlock } from '../qb/play'
import { problemText } from '../qb/problems'
import { refreshAfterBlocking, removeBlock } from './api'
import type { Blockable } from './BlockMenu'

/**
 * "You blocked this" (F-S8) on a publication's page: which of the caller's blocks cover it (the publication, its quiz,
 * its Sponsor, its video's provider), each with its unblock; the page is read again after each. Shown instead of the
 * page when the caller never started it, above it otherwise (an answered or unfinished attempt stays reachable).
 */
export function BlockedNotice({ item, blocks }: { item: Blockable; blocks: readonly PublicationBlock[] }) {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const unblock = useMutation({
    mutationFn: (blockId: string) => removeBlock(blockId),
    onSuccess: () => {
      notify.success(t('blocks.undone'))
      return refreshAfterBlocking(queryClient)
    },
    onError: (error) => notify.error(problemText(error)),
  })
  const sponsor = item.sponsorCompany ?? item.sponsorName
  const what = (block: PublicationBlock) => {
    switch (block.kind) {
      case 'PUBLICATION': return t('blocks.because.PUBLICATION')
      case 'QUIZ': return t('blocks.because.QUIZ')
      case 'SPONSOR': return sponsor ? t('blocks.because.SPONSOR', { name: sponsor }) : t('blocks.because.theSponsor')
      case 'PROVIDER': return item.videoProviderName
        ? t('blocks.because.PROVIDER', { name: item.videoProviderName }) : t('blocks.because.theProvider')
    }
  }
  return (
    <Alert data-testid="detail-blocked">
      <Ban aria-hidden />
      <AlertTitle>{t('blocks.noticeTitle')}</AlertTitle>
      <AlertDescription className="text-current flex flex-col gap-2">
        <p>{t('blocks.noticeHint')}</p>
        <ul className="flex flex-col gap-2">
          {blocks.map((block) => (
            <li key={block.blockId} className="flex flex-wrap items-center justify-between gap-2"
              data-testid="detail-blocked-item" data-kind={block.kind}>
              <span>{what(block)}</span>
              <Button size="sm" variant="outline" disabled={unblock.isPending} data-testid="detail-unblock"
                onClick={() => unblock.mutate(block.blockId)}>
                {t('blocks.unblock')}
              </Button>
            </li>
          ))}
        </ul>
      </AlertDescription>
    </Alert>
  )
}
