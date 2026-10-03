import type { StatementKind } from './api'

/** The report pages' paths (outside the platform's own `/reports`, decision D22). */
export const STATEMENTS_PATH = '/statements'
export const LINE_DETAIL_PATH = '/statements/lines'
export const DASHBOARD_PATH = '/finance-dashboard'

const query = (params: Record<string, unknown>) => {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

export const statementPath = (kind: StatementKind, params: Record<string, unknown> = {}) =>
  `${STATEMENTS_PATH}/${kind}${query(params)}`

export const lineDetailPath = (params: Record<string, unknown>) => `${LINE_DETAIL_PATH}${query(params)}`
