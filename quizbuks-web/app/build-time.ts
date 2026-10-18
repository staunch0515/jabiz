import { execFileSync } from 'node:child_process'

/**
 * When the app was built, as the About page shows it (U-74; Q7b review L5): the same sources give the same build, so
 * the time is that of the sources, not of the machine building them. `SOURCE_DATE_EPOCH` (seconds, the reproducible
 * builds' convention) when set, else the time of the commit built; null when neither is known (a copy without git),
 * and the page then shows none.
 */
export function buildTime(env: Record<string, string | undefined> = process.env,
  commitTime: () => string | null = lastCommitTime): string | null {
  const epoch = env.SOURCE_DATE_EPOCH?.trim()
  if (epoch && /^\d+$/.test(epoch)) return new Date(Number(epoch) * 1000).toISOString()
  const commit = commitTime()
  const time = commit ? Date.parse(commit) : Number.NaN
  return Number.isNaN(time) ? null : new Date(time).toISOString()
}

/** The committer time of the commit checked out where the build runs (ISO 8601), or null outside a git checkout. */
function lastCommitTime(): string | null {
  try {
    return execFileSync('git', ['log', '-1', '--format=%cI'], {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
    }).trim() || null
  } catch {
    return null
  }
}
