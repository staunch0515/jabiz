import { Input } from '@jabiz/ui'
import { Search } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'

/**
 * The search box over a list held in full in the browser (phase 17w): it narrows the rows as one types, and says how
 * many match when it does.
 */
export function ListSearch({ value, onChange, matched, total, testId = 'list-search' }: {
  value: string
  onChange: (value: string) => void
  matched: number
  total: number
  testId?: string
}) {
  const { t } = useTranslation()
  const id = useId()
  return (
    <div className="flex flex-wrap items-center gap-3">
      <div className="relative w-full max-w-xs">
        <Search aria-hidden className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
        <Input id={id} type="search" value={value} onChange={(event) => onChange(event.target.value)}
          placeholder={t('list.searchPlaceholder')} aria-label={t('list.search')} className="pl-8"
          data-testid={testId} />
      </div>
      {value.trim() && (
        <span className="text-muted-foreground text-sm" role="status" data-testid={`${testId}-count`}>
          {t('list.searchCount', { matched, total })}
        </span>
      )}
    </div>
  )
}
