import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Class names joined, later Tailwind utilities replacing conflicting earlier ones (shadcn/ui's helper). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * A class with no styles. It scoped Tailwind's preflight while Ant Design pages remained (phases 15a–15c); since phase
 * 15d the preflight is global and nothing carries or needs it. Still exported because extensions may name it
 * (`@jabiz/admin`, decision D22).
 *
 * @deprecated Not needed: every element is reset.
 */
export const UI_SCOPE = 'jabiz-ui'
