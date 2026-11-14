import assert from 'node:assert/strict'
import { test } from 'node:test'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { markers, scan } from './conflict-markers.mjs'

test('a marker at the start of a line is found, with its number', () => {
  const text = ['a', '<<<<<<< HEAD', 'b', '=======', 'c', '>>>>>>> origin/x', '=======', 'd'].join('\n')
  assert.deepEqual(markers(text).map((m) => m.line), [2, 4, 6, 7])
})

test('text that only looks like a marker is not one', () => {
  for (const text of ['x =======', '  =======', '======== title', '<<<<<<<<', '====== six', 'a<<<<<<< b', '==']) {
    assert.deepEqual(markers(text), [], text)
  }
})

test('a repository with a marker fails, a clean one passes', () => {
  const dir = mkdtempSync(join(tmpdir(), 'markers-'))
  execFileSync('git', ['init', '-q', dir])
  writeFileSync(join(dir, 'ok.md'), 'fine\n')
  execFileSync('git', ['-C', dir, 'add', '.'])
  assert.deepEqual(scan(dir), [])
  writeFileSync(join(dir, 'bad.md'), 'x\n=======\ny\n')
  execFileSync('git', ['-C', dir, 'add', '.'])
  assert.deepEqual(scan(dir), ['bad.md:2: ======='])
})

test('this repository has none', () => {
  assert.deepEqual(scan(join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', '..')), [])
})
