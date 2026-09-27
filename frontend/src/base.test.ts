import { describe, expect, it } from 'vitest'
import { routerBasename, viteBase } from './base'

describe('viteBase', () => {
  it('defaults to the root', () => {
    expect(viteBase(undefined)).toBe('/')
    expect(viteBase('')).toBe('/')
  })

  it('accepts sub-paths with a trailing slash', () => {
    expect(viteBase('/')).toBe('/')
    expect(viteBase('/admin/')).toBe('/admin/')
    expect(viteBase('/a/b-c/')).toBe('/a/b-c/')
  })

  it.each(['admin/', '/admin', '//', '/Admin/', '/a b/', './', 'https://cdn.example/'])('rejects %s', (value) => {
    expect(() => viteBase(value)).toThrow(/VITE_BASE/)
  })
})

describe('routerBasename', () => {
  it('drops the trailing slash of a sub-path', () => {
    expect(routerBasename('/')).toBe('/')
    expect(routerBasename('/admin/')).toBe('/admin')
    expect(routerBasename('/a/b/')).toBe('/a/b')
  })
})
