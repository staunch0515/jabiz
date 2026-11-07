import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { Combobox, EXTENSION_NAMESPACE, useAuth } from '@jabiz/admin'
import { searchUsers } from './api'
import { PATH, PERMISSION } from './ids'
import { OPERATORS_KEY, readOperator, readOperators } from './operator-api'

/** How long typing must pause before the server is asked. */
const DEBOUNCE_MS = 250
/** How many matches a picker offers: type more to narrow them (F-S1b: there may be many operators). */
const PICKER_LIMIT = 20

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** What was typed, once typing pauses. */
function useDebounced(typed: string) {
  const [value, setValue] = useState(typed)
  useEffect(() => {
    const timer = setTimeout(() => setValue(typed), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [typed])
  return value.trim()
}

interface PickerProps {
  id: string
  value: string | null
  onChange: (value: string | null) => void
  placeholder?: string
  /** A button that empties the field. */
  clearable?: boolean
  invalid?: boolean
  required?: boolean
  'aria-describedby'?: string
  'data-testid'?: string
}

/**
 * An operator chosen by its name (F-S1b): the server's first matches of what is typed (`qb.admin.operators`,
 * `qb.admin.operators.read`), however many operators there are. Name it with a `<Label htmlFor={id}>`.
 */
export function OperatorPicker({ id, value, onChange, placeholder, clearable, invalid, required,
  'data-testid': testId, ...aria }: PickerProps) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [typed, setTyped] = useState('')
  const q = useDebounced(typed)
  const found = useQuery({
    queryKey: [...OPERATORS_KEY, 'picker', q],
    queryFn: () => readOperators(0, PICKER_LIMIT, [{ field: 'name', asc: true }],
      q ? [{ field: 'name', op: 'like', value: `%${q}%` }] : undefined),
    placeholderData: keepPreviousData,
  })
  const current = useQuery({
    queryKey: [...OPERATORS_KEY, 'one', value],
    queryFn: () => readOperator(value as string),
    enabled: !!value,
  })
  const options = (found.data?.items ?? []).map((operator) => ({ value: operator.operatorId, label: operator.name,
    description: operator.contactEmail }))
  return (
    <Combobox {...aria} id={id} value={value} onChange={onChange} options={options} onSearch={setTyped}
      loading={found.isFetching} selectedLabel={current.data?.name} clearable={clearable} invalid={invalid}
      required={required} placeholder={placeholder ?? t('pickers.operator')}
      searchPlaceholder={t('pickers.operatorSearch')} emptyText={t('pickers.operatorNone')}
      data-testid={testId ?? 'operator-picker'} />
  )
}

/**
 * A user chosen by their name, address or company (`qb.admin.users`, `qb.admin.users.read`): the first matches of
 * what is typed. Name it with a `<Label htmlFor={id}>`.
 */
export function UserPicker({ id, value, onChange, placeholder, clearable, invalid, required,
  'data-testid': testId, ...aria }: PickerProps) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [typed, setTyped] = useState('')
  const q = useDebounced(typed)
  const found = useQuery({
    queryKey: ['qb-ext', 'users', 'picker', q],
    queryFn: () => searchUsers(q, 0, PICKER_LIMIT),
    enabled: q.length > 0,
    placeholderData: keepPreviousData,
  })
  const [labels, setLabels] = useState<Record<string, string>>({})
  const options = (q ? found.data ?? [] : []).map((user) => ({ value: user.userId,
    label: user.displayName ?? user.company ?? user.email ?? user.userName,
    description: [user.email, user.emailVerified ? null : t('pickers.unverified')].filter(Boolean).join(' · ') }))
  return (
    <Combobox {...aria} id={id} value={value} options={options} onSearch={setTyped} loading={found.isFetching}
      onChange={(next) => {
        const option = options.find((o) => o.value === next)
        if (next && option) setLabels((known) => ({ ...known, [next]: option.label }))
        onChange(next)
      }}
      selectedLabel={value ? labels[value] : undefined} clearable={clearable} invalid={invalid} required={required}
      placeholder={placeholder ?? t('pickers.user')} searchPlaceholder={t('pickers.userSearch')}
      emptyText={q ? t('pickers.userNone') : t('pickers.userType')} data-testid={testId ?? 'user-picker'} />
  )
}

/** A row's operator, linked to its page for who may open it. */
export function OperatorName({ id, name }: { id: string | null | undefined; name: string | null | undefined }) {
  const { can } = useAuth()
  if (!id) return <>—</>
  const label = name || id
  return can(PERMISSION.operatorsRead)
    ? <Link to={PATH.operator(id)} className={linkClass} data-testid="row-operator">{label}</Link>
    : <span data-testid="row-operator">{label}</span>
}

