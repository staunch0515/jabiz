import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Class names joined, later Tailwind utilities replacing conflicting earlier ones (shadcn/ui's helper). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * The class that scopes Tailwind's preflight (theme.css) while Ant Design pages remain: on the roots of the shell and
 * of every portal (dialogs, menus, popovers, tooltips, toasts), which render outside the shell. Phase 15d makes the
 * preflight global and removes it.
 */
export const UI_SCOPE = 'jabiz-ui'
