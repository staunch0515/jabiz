import { createContext, useContext } from 'react'

/**
 * How the entry pages look (phase 17zz30, 12 §13.1): `card` (the default) is the form in its card; `open` is the
 * timeline look, no card, pill buttons and fields with their label inside.
 */
export type EntryLook = 'card' | 'open'

/** The look of the entry page around a form, set by `EntryLayout`. */
export const EntryLookContext = createContext<EntryLook>('card')

/** The look of the entry page this form is on. */
export function useEntryLook(): EntryLook {
  return useContext(EntryLookContext)
}
