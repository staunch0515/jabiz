import { formatDateTime } from '@jabiz/client'
import { Button } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { History as HistoryIcon } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { historyQuery, readHistory, type Side, type VideoUse } from './api'
import { period } from './parts'
import './messages'

/**
 * F-S14: the history of a use, on both sides' pages: every version oldest first, the act (written, corrected, sent,
 * confirmed, sent back), the side that acted, when, the entry as it then was and the reason sent back. Shown on
 * request; read from the use's own append-only versions (`qb.sponsor.video-use-history[-provided]`).
 */
export function VideoUseHistory({ side, use, userName, providerName }: {
  side: Side
  use: VideoUse
  /** How the page calls the two sides: "you" for the reader's own, the other's company. */
  userName: string
  providerName: string
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const [open, setOpen] = useState(false)
  const id = useId()
  const history = useQuery({ queryKey: historyQuery(side, use.videoUseId, use.revision),
    queryFn: () => readHistory(side, use.videoUseId), enabled: open })
  return (
    <div className="flex flex-col gap-2">
      <div>
        <Button type="button" variant="ghost" size="sm" aria-expanded={open} aria-controls={id}
          onClick={() => setOpen((shown) => !shown)} data-testid="video-use-history-toggle">
          <HistoryIcon aria-hidden className="size-4" />
          {open ? t('videoUses.history.hide') : t('videoUses.history.show')}
        </Button>
      </div>
      {open && (
        <div id={id} data-testid="video-use-history">
          {history.isError && <p className="text-destructive text-sm" role="alert">{problemText(history.error)}</p>}
          {history.data && (
            <ol className="flex flex-col gap-2 border-l pl-4" aria-label={t('videoUses.history.title')}>
              {history.data.map((entry) => (
                <li key={entry.versionNo} className="flex flex-col gap-0.5 text-sm" data-testid="video-use-history-entry"
                  data-act={entry.act}>
                  <span>
                    <span className="font-medium">{t(`videoUses.history.act.${entry.act}`)}</span>
                    {' · '}{entry.by === 'user' ? userName : providerName}
                    {' · '}<span className="text-muted-foreground">{formatDateTime(entry.recordedAt)}</span>
                  </span>
                  <span className="text-muted-foreground">
                    {t('videoUses.revision', { revision: entry.revision })}
                    {' · '}{t(`videoUses.priceOf.${entry.pricing}`, { price: entry.price.toLocaleString(i18n.language) })}
                    {' · '}{period(entry)}
                  </span>
                  {entry.act === 'REJECTED' && entry.rejectReason && (
                    <span className="whitespace-pre-wrap" data-testid="video-use-history-reason">
                      {t('videoUses.history.reason', { reason: entry.rejectReason })}</span>
                  )}
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </div>
  )
}
