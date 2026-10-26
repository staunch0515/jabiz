import { Button, SegmentedControl, cn } from '@jabiz/ui'
import { ChevronLeft, ChevronRight, LayoutGrid, Rows3 } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from './messages'

/** How a list shows its items (Q34b): cards with large pictures, or the table for many items at once. */
export type ListView = 'cards' | 'table'

function stored(key: string): ListView {
  try {
    return window.localStorage.getItem(key) === 'table' ? 'table' : 'cards'
  } catch {
    return 'cards'
  }
}

/** The list's view, cards by default, remembered on this device per list. */
export function useListView(list: string): [ListView, (view: ListView) => void] {
  const key = `qb.sponsor.view.${list}`
  const [view, setView] = useState<ListView>(() => stored(key))
  return [view, (next) => {
    setView(next)
    try {
      window.localStorage.setItem(key, next)
    } catch {
      // Blocked storage: the choice lasts for the page.
    }
  }]
}

/** The switch between the two views. */
export function ViewSwitch({ value, onChange, testId }: { value: ListView; onChange: (view: ListView) => void;
  testId?: string }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <SegmentedControl<ListView> value={value} onChange={onChange} aria-label={t('lists.view')} data-testid={testId}
      options={[
        { value: 'cards', label: <span className="flex items-center gap-1.5"><LayoutGrid aria-hidden className="size-4" />{t('lists.cards')}</span> },
        { value: 'table', label: <span className="flex items-center gap-1.5"><Rows3 aria-hidden className="size-4" />{t('lists.table')}</span> },
      ]} />
  )
}

/** The grid of cards: two columns on a narrow screen up to four on a wide one. */
export function CardGrid({ label, children, className, testId }: { label: string; children: ReactNode;
  className?: string; testId?: string }) {
  return (
    <ul aria-label={label} className={cn('grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4', className)}
      data-testid={testId}>
      {children}
    </ul>
  )
}

/** The pages of the card view: back, where, forward (the table has its own). */
export function Pager({ pageIndex, pageSize, total, onPage }: { pageIndex: number; pageSize: number;
  total: number | undefined; onPage: (index: number) => void }) {
  const { t } = useTranslation(SPONSOR)
  const pages = Math.max(1, Math.ceil((total ?? 0) / pageSize))
  if (pages <= 1) return null
  return (
    <nav aria-label={t('lists.pages')} className="flex items-center justify-end gap-2" data-testid="card-pager">
      <Button variant="outline" size="icon" aria-label={t('lists.previous')} disabled={pageIndex === 0}
        onClick={() => onPage(pageIndex - 1)}>
        <ChevronLeft aria-hidden />
      </Button>
      <span className="text-muted-foreground text-sm" aria-live="polite">
        {t('lists.page', { page: pageIndex + 1, pages })}
      </span>
      <Button variant="outline" size="icon" aria-label={t('lists.next')} disabled={pageIndex + 1 >= pages}
        onClick={() => onPage(pageIndex + 1)}>
        <ChevronRight aria-hidden />
      </Button>
    </nav>
  )
}
