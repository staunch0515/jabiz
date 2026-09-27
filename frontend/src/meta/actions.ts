import type { ProcessEntry } from './types'

/**
 * Processes offered as actions on an entity's rows (docs/design/16-content-authoring.md section 3). The catalog
 * already holds only the processes the caller may run; the condition is a display hint, the server decides.
 */
export function actionsFor(entity: string, processes: ProcessEntry[]): ProcessEntry[] {
  return processes.filter((p) => p.actsOn?.entity === entity && p.latest && !p.deprecated)
}

/** Whether the action is shown for an instance with these attributes. */
export function actionShown(process: ProcessEntry, attributes: Record<string, unknown>): boolean {
  const when = process.actsOn?.when
  if (!when?.field) return true
  const value = attributes[when.field]
  return value !== null && value !== undefined && (when.values ?? []).includes(String(value))
}

/** Whether the process needs nothing but the instance's key: it then runs after a confirmation, without a form. */
export function needsOnlyTheKey(process: ProcessEntry): boolean {
  const properties = Object.keys((process.input as { properties?: Record<string, unknown> })?.properties ?? {})
  return properties.length === 1 && properties[0] === process.actsOn?.input
}
