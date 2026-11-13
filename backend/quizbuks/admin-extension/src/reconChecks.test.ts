// @vitest-environment node
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { ATTENTION_CHECKS, NOTE_CHECKS } from './ids'
import { messages } from './messages'

/** The backend's list of checks and its levels (F-M14): read from the Java, so that the two cannot drift apart. */
const JAVA = readFileSync(fileURLToPath(new URL(
  '../../src/main/java/com/jabiz/quizbuks/wallet/ReconChecks.java', import.meta.url)), 'utf8')

/** The names in `List.of(...)` / `Set.of(...)` after `declaration`, resolved to their string values. */
function constants(declaration: string): string[] {
  const start = JAVA.indexOf(declaration)
  expect(start, declaration).toBeGreaterThan(-1)
  const end = JAVA.indexOf(');', start)
  const body = JAVA.slice(JAVA.indexOf('.of(', start) + 4, end)
  return [...body.matchAll(/[A-Z][A-Z0-9_]+/g)].map(([name]) => {
    const value = new RegExp(`String ${name} = "([A-Z0-9_]+)";`).exec(JAVA)
    expect(value, name).not.toBeNull()
    return value![1]
  })
}

describe('ReconChecks.java', () => {
  const all = constants('List<String> ALL =')

  it('lists the checks the page knows (52 at the time of writing: more is fine, none may be lost)', () => {
    expect(all.length).toBeGreaterThanOrEqual(52)
    expect(new Set(all).size).toBe(all.length)
  })

  it('has the levels the page mirrors in ids.ts', () => {
    expect([...ATTENTION_CHECKS].sort()).toEqual(constants('Set<String> ATTENTION =').sort())
    expect([...NOTE_CHECKS].sort()).toEqual(constants('Set<String> NOTES =').sort())
    for (const check of [...ATTENTION_CHECKS, ...NOTE_CHECKS]) expect(all, check).toContain(check)
  })

  it.each(['en', 'zh', 'ja'] as const)('has a "what to do" sentence in %s for every check', (language) => {
    const help = (messages[language] as { finance: { recon: { help: Record<string, string> } } }).finance.recon.help
    for (const check of all) expect(help[check]?.trim(), check).toBeTruthy()
    expect(Object.keys(help).sort()).toEqual([...all].sort())
  })
})
