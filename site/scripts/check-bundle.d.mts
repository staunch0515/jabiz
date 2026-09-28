import type { Plugin } from 'vite'

export const BUDGET: number
export function firstScreenScripts(html: string): string[]
export function measure(dist: string, base?: string): { file: string; gzip: number }[]
export function report(dist: string, base?: string, log?: (line: string) => void): boolean
export function firstScreenBudget(): Plugin
