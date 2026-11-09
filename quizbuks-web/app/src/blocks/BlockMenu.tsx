import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  notify,
} from '@jabiz/ui'
import { useAuth } from '@jabiz/client'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Ban, EllipsisVertical, EyeOff } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import type { BlockKind } from '../qb/play'
import { problemText } from '../qb/problems'
import { addBlock, refreshAfterBlocking, removeBlock, type BlockOutput } from './api'

/** What of a card or page the menu offers to block (the feed's card and the page carry the same). */
export interface Blockable {
  publicationId: string
  name: string
  quizId?: string | null
  sponsorCompany: string | null
  sponsorName: string | null
  videoProviderId?: string | null
  videoProviderName?: string | null
}

/** Blocks and offers to undo it (the undo removes only what this block made). */
export function useBlock(onBlocked?: (kind: BlockKind, output: BlockOutput) => void) {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ publicationId, kind }: { publicationId: string; kind: BlockKind }) => addBlock(publicationId, kind),
    onSuccess: (output, { kind }) => {
      onBlocked?.(kind, output)
      const undo = () => {
        removeBlock(output.blockId)
          .then(() => notify.success(t('blocks.undone')))
          .catch((error: unknown) => notify.error(problemText(error)))
          .finally(() => void refreshAfterBlocking(queryClient))
      }
      notify.success(t(`blocks.done.${kind}`), output.created
        ? { action: { label: t('blocks.undo'), onClick: undo }, duration: 8000 } : undefined)
      return refreshAfterBlocking(queryClient)
    },
    onError: (error) => notify.error(problemText(error)),
  })
}

/**
 * The menu of a card, the vertical mode's screen or a publication's page (F-S8): "not interested" (the publication),
 * every publication of its quiz, its Sponsor, and its shared video's provider when it has one. Each is undone from the
 * message that follows, or later in the app's settings. `onBlocked` is told first (the vertical mode moves on). Not
 * shown to an unverified account: blocking is a write, which needs a verified address (D-Q40-4).
 */
export function BlockMenu({ item, onBlocked, className }: { item: Blockable
  onBlocked?: (kind: BlockKind, output: BlockOutput) => void
  className?: string }) {
  const { t } = useTranslation(APP)
  const { emailVerified } = useAuth()
  const block = useBlock(onBlocked)
  const sponsor = item.sponsorCompany ?? item.sponsorName
  if (!emailVerified) return null
  const choose = (kind: BlockKind) => block.mutate({ publicationId: item.publicationId, kind })
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon-sm" className={className} disabled={block.isPending}
          aria-label={t('blocks.menu', { name: item.name })} data-testid="block-menu">
          <EllipsisVertical aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onSelect={() => choose('PUBLICATION')} data-testid="block-PUBLICATION">
          <EyeOff aria-hidden />
          {t('blocks.notInterested')}
        </DropdownMenuItem>
        {item.quizId && (
          <DropdownMenuItem onSelect={() => choose('QUIZ')} data-testid="block-QUIZ">
            <Ban aria-hidden />
            {t('blocks.blockQuiz')}
          </DropdownMenuItem>
        )}
        <DropdownMenuItem onSelect={() => choose('SPONSOR')} data-testid="block-SPONSOR">
          <Ban aria-hidden />
          {sponsor ? t('blocks.blockSponsor', { name: sponsor }) : t('blocks.blockTheSponsor')}
        </DropdownMenuItem>
        {item.videoProviderId && (
          <DropdownMenuItem onSelect={() => choose('PROVIDER')} data-testid="block-PROVIDER">
            <Ban aria-hidden />
            {item.videoProviderName ? t('blocks.blockProvider', { name: item.videoProviderName })
              : t('blocks.blockTheProvider')}
          </DropdownMenuItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
