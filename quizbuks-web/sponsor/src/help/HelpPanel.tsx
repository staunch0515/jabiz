import { useAuth } from '@jabiz/client'
import { Button, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from '@jabiz/ui'
import { CircleHelp } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { HelpChat } from '../qb/HelpChat'
import { HELP } from '../qb/ids'
import { QB } from '../qb/messages'
import { SPONSOR } from '../messages'
import './messages'
import { sponsorPageName } from './pages'

/** The event that opens the panel, with a conversation (an inbox message's) or none. */
const OPEN_EVENT = 'qb:help-open'

/** Opens the help's panel, on a conversation (a person's reply in the inbox) or on a new one. */
export function openHelp(threadId: string | null = null) {
  window.dispatchEvent(new CustomEvent<string | null>(OPEN_EVENT, { detail: threadId }))
}

/**
 * The header's "?" (F-C10c): the AI help in a panel at the right (the whole screen on a phone), over whatever page is
 * open, told that page's name (`pages.ts`). The conversation stays while the panel is closed and opened again; a link
 * of an answer to another page closes it. The panel does not depend on the frame: F-F3 moves the button as it is.
 */
export function HelpPanel({ path, search }: { path: string; search: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const { permissions } = useAuth()
  const [open, setOpen] = useState(false)
  const [threadId, setThreadId] = useState<string | null>(null)
  useEffect(() => {
    const opened = (event: Event) => {
      const detail = (event as CustomEvent<string | null>).detail
      if (detail) setThreadId(detail)
      setOpen(true)
    }
    window.addEventListener(OPEN_EVENT, opened)
    return () => window.removeEventListener(OPEN_EVENT, opened)
  }, [])
  if (!permissions.has(HELP.use)) return null
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button type="button" variant="ghost" size="icon" aria-label={t('helpPanel.open')} data-testid="help-open">
          <CircleHelp aria-hidden />
        </Button>
      </SheetTrigger>
      <SheetContent side="right" className="w-full overflow-y-auto sm:max-w-md" data-testid="help-panel">
        <SheetHeader>
          <SheetTitle>{tQb('helpAi.title')}</SheetTitle>
          <SheetDescription>{tQb('helpAi.intro')}</SheetDescription>
        </SheetHeader>
        <div className="px-4 pb-6">
          <HelpChat entry="sponsor" page={sponsorPageName(path, search)} threadId={threadId}
            onThreadChange={setThreadId} heading={false} onFollowLink={() => setOpen(false)} />
        </div>
      </SheetContent>
    </Sheet>
  )
}
