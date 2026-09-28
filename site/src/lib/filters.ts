/** The filters of a list page, kept in its address so that a filtered list can be shared (design section 9.3). */
export type FilterState = Record<string, string[]>

export function readFilters(search: URLSearchParams, names: readonly string[]): FilterState {
  const state: FilterState = {}
  for (const name of names) {
    state[name] = [...new Set(search.getAll(name).filter((v) => v !== ''))]
  }
  return state
}

/** The address's query string with one group's choices replaced; other parameters are kept. */
export function writeFilter(search: URLSearchParams, name: string, values: readonly string[]): URLSearchParams {
  const next = new URLSearchParams(search)
  next.delete(name)
  for (const value of values) next.append(name, value)
  return next
}

export function clearFilters(search: URLSearchParams, names: readonly string[]): URLSearchParams {
  const next = new URLSearchParams(search)
  names.forEach((name) => next.delete(name))
  return next
}

/** A list parameter of a template: absent when nothing is chosen, so that the template does not filter. */
export function listParam(values: readonly string[]): readonly string[] | undefined {
  return values.length > 0 ? values : undefined
}
