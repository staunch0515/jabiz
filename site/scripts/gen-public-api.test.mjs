import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { render, tsType } from './gen-public-api.mjs'

test('kinds map to the JSON the public interface sends', () => {
  assert.equal(tsType({ type: 'text', maxLength: 40 }), 'string')
  assert.equal(tsType({ type: 'semanticIdentity', urn: 'urn:x' }), 'string')
  assert.equal(tsType({ type: 'temporal', role: 'EVENT_TIME' }), 'string')
  assert.equal(tsType({ type: 'numeric', precision: 9, scale: 0 }), 'number')
  assert.equal(tsType({ type: 'bool' }), 'boolean')
  assert.equal(tsType({ type: 'code', allowedValues: ['PHOTO', 'AUDIO'] }), '"PHOTO" | "AUDIO"')
  // A database dictionary grows without a release.
  assert.equal(tsType({ type: 'code', dictUrn: 'urn:d', allowedValues: [] }), 'string')
  assert.equal(tsType({ type: 'custom', kindId: 'jabiz.i18n-text' }), 'LocalizedText')
  assert.equal(tsType({ type: 'custom', kindId: 'jabiz.file' }), 'FileId')
  assert.throws(() => tsType({ type: 'custom', kindId: 'jabiz.unknown' }))
})

test('a template becomes its parameters, row, filters and sorts', () => {
  const source = render({
    queries: [{
      id: 'culture.public.stories',
      description: 'The archive',
      params: {
        slug: { kind: { type: 'text' }, list: false, required: true },
        theme: { kind: { type: 'text' }, list: true, required: false },
      },
      results: { title: { type: 'custom', kindId: 'jabiz.i18n-text' }, storyCount: { type: 'numeric' } },
      list: { filters: ['mediaType'], sorts: [] },
      cacheSeconds: 60,
    }],
  })
  assert.match(source, /\/\*\* The archive \*\/\n {2}"culture\.public\.stories": \{/)
  assert.match(source, /slug: string\n/)
  assert.match(source, /theme\?: readonly \(string\)\[\]/)
  assert.match(source, /title: LocalizedText \| null/)
  assert.match(source, /storyCount: number \| null/)
  assert.match(source, /filters: "mediaType"/)
  assert.match(source, /sorts: never/)
  assert.match(source, /"culture\.public\.stories": 60,/)
})

test('templates without parameters take none', () => {
  const source = render({
    queries: [{ id: 'q', params: {}, results: {}, list: { filters: [], sorts: [] }, cacheSeconds: 0 }],
  })
  assert.match(source, /params: Record<string, never>/)
})
