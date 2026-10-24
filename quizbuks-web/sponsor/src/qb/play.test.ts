import { describe, expect, it } from 'vitest'
import { numberOf, parseMaterials } from './play'

describe('reading answering\'s templates', () => {
  it('reads numbers given as numbers or as decimal text', () => {
    expect(numberOf(3)).toBe(3)
    expect(numberOf('12')).toBe(12)
    expect(numberOf(null)).toBe(0)
    expect(numberOf(undefined)).toBe(0)
    expect(numberOf('x')).toBe(0)
  })

  it('reads the materials of a version\'s snapshot, each with its one content', () => {
    const json = JSON.stringify([
      { no: 1, kind: 'ARTICLE', title: 'Read me', description: null, body: '**Bold**', url: null, pdf: null,
        audio: null, images: [] },
      { no: 2, kind: 'VIDEO_LINK', title: null, description: 'A clip', body: null, url: 'https://example.com/v',
        pdf: null, audio: null, images: [] },
      { no: 3, kind: 'IMAGES', title: 'Maps', description: null, body: null, url: null, pdf: null, audio: null,
        images: [{ no: 1, image: 'f1', caption: 'North' }, { no: 2, image: null, caption: 'none' }] },
    ])
    const materials = parseMaterials(json)
    expect(materials.map((m) => m.kind)).toEqual(['ARTICLE', 'VIDEO_LINK', 'IMAGES'])
    expect(materials[0].body).toBe('**Bold**')
    expect(materials[1]).toMatchObject({ title: null, description: 'A clip', url: 'https://example.com/v' })
    // An image without a file is left out.
    expect(materials[2].images).toEqual([{ no: 1, image: 'f1', caption: 'North' }])
  })

  it('reads no materials from nothing or from something that is not a list', () => {
    expect(parseMaterials(null)).toEqual([])
    expect(parseMaterials('')).toEqual([])
    expect(parseMaterials('{')).toEqual([])
    expect(parseMaterials('{"a":1}')).toEqual([])
  })
})
