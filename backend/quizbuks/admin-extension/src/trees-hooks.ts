import { useTranslation } from 'react-i18next'
import { EXTENSION_NAMESPACE, useAuth } from '@jabiz/admin'
import { PERMISSION } from './ids'
import type { Condition } from './trees-api'

/**
 * Whether the session reads its own operator's maps (`qb.operator.trees`): an operator's member, not the platform
 * (whose `qb.admin.trees.read` reads every map).
 */
export function useOperatorTrees() {
  const { can } = useAuth()
  return !can(PERMISSION.treesRead) && can(PERMISSION.operatorUse)
}

/** A node's condition as a sentence, the nodes it names by their names in the version. */
export function useConditionText() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (condition: Condition | null, nameOf: (nodeId: string) => string): string => {
    const text = (c: Condition): string => {
      if (c.kind === 'passed') return t('trees.condition.passed')
      if (c.kind === 'node') {
        return t(c.beforeAnswer ? 'trees.condition.nodeBefore' : 'trees.condition.node', { node: nameOf(c.nodeId) })
      }
      const items = c.items.map((item) => (item.kind === 'group' ? `(${text(item)})` : text(item))).join(', ')
      if (c.op === 'ANY') return t('trees.condition.any', { items })
      if (c.op === 'AT_LEAST') return t('trees.condition.atLeast', { n: c.n, items })
      return t('trees.condition.all', { items })
    }
    return condition ? text(condition) : t('trees.condition.unreadable')
  }
}
