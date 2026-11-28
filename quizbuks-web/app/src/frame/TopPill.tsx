import { useServiceWorkerUpdate } from '@jabiz/client'
import { RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'

/**
 * 2026-11-28 (the requester: the bands at the top of the home were a nuisance): x.com's "Show new posts", a pill that
 * floats under the header in the middle of the column, takes no room of the page and goes once tapped. One at a time:
 * a new version of the app, else what waits for the taker on the home (`HomeNotice`).
 */
export function TopPill({ icon, label, onClick, testId }: {
  icon: ReactNode
  label: string
  onClick: () => void
  testId: string
}) {
  return (
    <div role="status" className="pointer-events-none sticky top-[4.25rem] z-20 flex h-0 justify-center"
      data-testid={`${testId}-place`}>
      <button type="button" onClick={onClick} data-testid={testId}
        className="bg-primary text-primary-foreground focus-visible:ring-ring pointer-events-auto flex h-9 max-w-[calc(100%-2rem)] items-center gap-2 rounded-full px-4 text-[15px] leading-5 font-bold shadow-md outline-none hover:opacity-90 focus-visible:ring-2 focus-visible:ring-offset-2 [&_svg]:size-4 [&_svg]:shrink-0">
        {icon}
        <span className="truncate">{label}</span>
      </button>
    </div>
  )
}

/** A new version of the app is waiting: a tap takes it (the platform's Service Worker, updated with consent). */
export function UpdatePill() {
  const { t } = useTranslation(APP)
  const update = useServiceWorkerUpdate()
  if (!update) return null
  return <TopPill icon={<RefreshCw aria-hidden />} label={t('notices.update')} onClick={() => update()}
    testId="app-update" />
}

/** Whether a new version waits (the home then keeps its own pill back: one at a time). */
export function useUpdateWaiting(): boolean {
  return !!useServiceWorkerUpdate()
}
