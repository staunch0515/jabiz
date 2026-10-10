import { describe, expect, it } from 'vitest'
import { defineExtension } from './api'
import { checkedExtension, extensionMenu, extensionProblems, homePath } from './registry'

const label = (key: string) => `<${key}>`

describe('extensionProblems', () => {
  it('accepts no extension and a well-formed one', () => {
    expect(extensionProblems({})).toEqual([])
    expect(
      extensionProblems(
        defineExtension({
          routes: [{ path: '/gl', children: [{ path: 'journals' }, { path: '/gl/close' }] }],
          menu: [{ key: 'gl', label: 'nav.gl', path: '/gl', children: [{ key: 'close', label: 'nav.close', path: '/gl/close' }] }],
          home: '/gl',
        }),
      ),
    ).toEqual([])
  })

  it('reports every problem at once', () => {
    const problems = extensionProblems({
      routes: [
        { path: '/data/mine' },
        { path: 'relative' },
        { index: true },
        { element: null },
        { path: '/twice' },
        { path: '/twice' },
        { path: '/' },
        { path: '/processes' },
        { path: '/login' },
        { path: '/tasks/mine' },
      ],
      menu: [
        { key: 'a', label: 'x', path: 'no-slash' },
        { key: 'a', label: '' },
      ],
      home: 'home',
    })
    expect(problems).toEqual([
      'an extension route cannot be the index route; use "home"',
      'an extension route needs a path',
      'route "/data/mine" is a path of the platform',
      'route "relative" is not an absolute path',
      'route "/twice" is declared twice',
      'route "/" is a path of the platform',
      'route "/processes" is a path of the platform',
      'route "/login" is a path of the platform',
      'route "/tasks/mine" is a path of the platform',
      'a menu entry needs a key and a label',
      'menu key "a" is declared twice',
      '"no-slash" is not an absolute path',
      '"home" is not an absolute path',
    ])
  })

  it('does not mistake a longer name for a platform path', () => {
    expect(extensionProblems({ routes: [{ path: '/database' }, { path: '/processes-report' }] })).toEqual([])
  })

  it('stops the application when the extension is broken', () => {
    expect(() => checkedExtension({ routes: [{ path: '/data' }] })).toThrow(/route "\/data" is a path of the platform/)
    const fine = { routes: [{ path: '/fine' }] }
    expect(checkedExtension(fine)).toBe(fine)
  })
})

describe('extensionMenu', () => {
  const menu = [
    { key: 'stock', label: 'menu.stock', path: '/stock', permission: 'stock.read' },
    {
      key: 'gl',
      label: 'menu.gl',
      children: [
        { key: 'close', label: 'menu.close', path: '/gl/close', permission: 'fin.period.close' },
        { key: 'journal', label: 'menu.journal', path: '/gl/journal' },
      ],
    },
    { key: 'admin', label: 'menu.admin', children: [{ key: 'users', label: 'menu.users', path: '/u', permission: 'x' }] },
  ]

  it('shows what the user may use, labelled, under keys of its own', () => {
    const items = extensionMenu(menu, (p) => p === 'stock.read', label)
    expect(items).toEqual([
      { key: 'ext:stock', label: '<menu.stock>', path: '/stock', icon: undefined, children: undefined },
      {
        key: 'ext:gl',
        label: '<menu.gl>',
        path: '/ext/gl',
        icon: undefined,
        children: [{ key: 'ext:journal', label: '<menu.journal>', path: '/gl/journal', icon: undefined, children: undefined }],
      },
    ])
  })

  it('is empty without an extension menu', () => {
    expect(extensionMenu(undefined, () => true, label)).toEqual([])
  })
})

describe('homePath', () => {
  it('is the data catalog unless the extension names a home', () => {
    expect(homePath({})).toBe('/data')
    expect(homePath({ home: '/gl' })).toBe('/gl')
  })
})
