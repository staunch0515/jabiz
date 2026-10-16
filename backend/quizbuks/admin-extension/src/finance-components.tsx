import { useQuery } from '@tanstack/react-query'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import {
  EXTENSION_NAMESPACE,
  Input,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@jabiz/admin'
import { readDictionary } from './api'

/** The value of the state filter that chooses every state (Radix's select has no empty value). */
const ALL = '*'

/** A state filter: every item of a dictionary, or all. */
export function StatusFilter({ dictionary, value, onChange, testId }: {
  dictionary: string
  value: string | null
  onChange: (value: string | null) => void
  testId: string
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const items = useQuery({ queryKey: ['qb-ext', 'dictionary-items', dictionary], queryFn: () =>
    readDictionary(dictionary), staleTime: 5 * 60_000 })
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={id}>{t('finance.filter.status')}</Label>
      <Select value={value ?? ALL} onValueChange={(next) => onChange(next === ALL ? null : next)}>
        <SelectTrigger id={id} className="w-48" data-testid={testId}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL}>{t('finance.filter.all')}</SelectItem>
          {(items.data ?? []).map((item) => <SelectItem key={item.code} value={item.code}>{item.label}</SelectItem>)}
        </SelectContent>
      </Select>
    </div>
  )
}

/** A period of whole days (UTC, both included), as two date fields. */
export function DayRange({ from, to, onChange }: {
  from: string | null
  to: string | null
  onChange: (from: string | null, to: string | null) => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const fromId = useId()
  const toId = useId()
  return (
    <>
      <div className="flex flex-col gap-1">
        <Label htmlFor={fromId}>{t('finance.filter.from')}</Label>
        <Input id={fromId} type="date" className="w-44" value={from ?? ''} data-testid="filter-from"
          onChange={(event) => onChange(event.target.value || null, to)} />
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor={toId}>{t('finance.filter.to')}</Label>
        <Input id={toId} type="date" className="w-44" value={to ?? ''} data-testid="filter-to"
          onChange={(event) => onChange(from, event.target.value || null)} />
      </div>
    </>
  )
}
