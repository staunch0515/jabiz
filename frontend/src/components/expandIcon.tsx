import type { TableProps } from 'antd'
import type { TFunction } from 'i18next'

type ExpandIconProps = Parameters<NonNullable<NonNullable<TableProps['expandable']>['expandIcon']>>[0]

/**
 * The expand button of a table's rows, named for screen readers (WCAG 4.1.2; docs/design/12-frontend.md section 11):
 * in the ProTables with expandable rows Ant Design's own button had no name. Same classes, so it looks the same. Give
 * it only to tables with expandedRowRender: a table given an expandIcon without one is treated as a tree. A render
 * function, not a component (the table calls it per row), so it takes the texts from its caller.
 */
export function expandIcon(t: TFunction) {
  return function ExpandIcon({ prefixCls, onExpand, record, expanded, expandable }: ExpandIconProps) {
    const icon = `${prefixCls}-row-expand-icon`
    return (
      <button
        type="button"
        className={[icon, !expandable && `${icon}-spaced`, expandable && (expanded ? `${icon}-expanded` : `${icon}-collapsed`)]
          .filter(Boolean)
          .join(' ')}
        aria-label={t(expanded ? 'app.collapseRow' : 'app.expandRow')}
        aria-expanded={expanded}
        onClick={(e) => {
          onExpand(record, e)
          e.stopPropagation()
        }}
      />
    )
  }
}
