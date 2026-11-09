import { createContext, useContext } from 'react'
import type { ShellState } from './areas'

/** What the frame knows for the pages in it (F-F3): which areas and tabs show. */
export const ShellContext = createContext<ShellState>({ kudos: true, benefits: true })

export function useShellState(): ShellState {
  return useContext(ShellContext)
}
