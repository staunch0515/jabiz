// A merge that leaves a conflict marker at the start of a line (`<<<<<<< `, `=======`, `>>>>>>> `) is caught here:
// every tracked text file is scanned (the 7-character markers of git, followed by a space or the end of the line).
// Run with `node tools/quizbuks/ci/conflict-markers.mjs [root]`; exits 1 and lists file:line when one is found.
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const MARKER = /^(<{7}|={7}|>{7})( .*)?$/

/** The marker lines of a text: [{ line, text }]. */
export function markers(text) {
  const found = []
  text.split(/\r?\n/).forEach((line, index) => {
    if (MARKER.test(line)) found.push({ line: index + 1, text: line })
  })
  return found
}

export function scan(root) {
  const files = execFileSync('git', ['-C', root, 'ls-files', '-z'], { maxBuffer: 1 << 28 }).toString().split('\0')
  const problems = []
  for (const file of files) {
    if (!file) continue
    let buffer
    try {
      buffer = readFileSync(join(root, file))
    } catch {
      continue
    }
    if (buffer.includes(0)) continue // binary
    for (const found of markers(buffer.toString('utf8'))) problems.push(`${file}:${found.line}: ${found.text}`)
  }
  return problems
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = process.argv[2] ?? join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', '..')
  const problems = scan(root)
  if (problems.length > 0) {
    console.error('Conflict markers left in the repository:\n' + problems.join('\n'))
    process.exit(1)
  }
  console.log('No conflict markers.')
}
