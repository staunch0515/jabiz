import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { EXTENSION_NAMESPACE, Label, useAuth, type ColumnDef, type Filter } from '@jabiz/admin'
import { PERMISSION } from './ids'
import { OperatorName, OperatorPicker } from './pickers'

/** The sign-in entry of the Operator Management System (`/operator/`; backend `jabiz.security.entries.operator`). */
export const OPERATOR_ENTRY = 'operator'

/**
 * Whether the page reads every operator's rows, as the platform does (F-S1b): a session of the Operator Management
 * System reads only its own sponsors' (the server's scope), so it is offered no filter by operator and no operator
 * column.
 */
export function useAcrossOperators() {
  const { entry } = useAuth()
  return entry !== OPERATOR_ENTRY
}

/**
 * The filter by operator of a list the platform reads across operators (F-S1b): the operator in the address
 * (`?operator=`), the template's filter on `operatorId` (the sponsor's operator now) and the picker to show. Nothing
 * for an operator's session.
 */
export function useOperatorFilter(testId = 'operator-filter') {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const across = useAcrossOperators()
  // Choosing one needs the operators' list.
  const choosable = across && can(PERMISSION.operatorsRead)
  const [search, setSearch] = useSearchParams()
  const operatorId = choosable ? search.get('operator') : null
  const filters: Filter[] = operatorId ? [{ field: 'operatorId', op: 'eq', value: operatorId }] : []
  const id = `${testId}-input`
  const bar = choosable ? (
    <div className="flex w-72 flex-col gap-1">
      <Label htmlFor={id}>{t('pickers.byOperator')}</Label>
      <OperatorPicker id={id} value={operatorId} clearable placeholder={t('pickers.allOperators')}
        data-testid={testId} onChange={(value) => {
          const next = new URLSearchParams(search)
          if (value) next.set('operator', value)
          else next.delete('operator')
          setSearch(next, { replace: true })
        }} />
    </div>
  ) : null
  return { across, operatorId, filters, bar }
}

/** The operator column of a list the platform reads across operators (F-S1b). */
export function operatorColumn<Row extends { operatorId?: string | null; operatorName?: string | null }>(
  header: string): ColumnDef<Row, unknown> {
  return { id: 'operator', header, cell: ({ row }) => (
    <OperatorName id={row.original.operatorId} name={row.original.operatorName} />
  ) }
}
