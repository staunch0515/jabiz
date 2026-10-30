import { describe, expect, it } from 'vitest'
import { applyBrandHead, brandName } from './brand'

describe('the brand (phase 17v)', () => {
  it('names the frontend in the language chosen, else the first name given; none without a brand', () => {
    expect(brandName(undefined, 'en')).toBeNull()
    expect(brandName({ name: ' Acme ' }, 'ja')).toBe('Acme')
    expect(brandName({ name: '   ' }, 'en')).toBeNull()
    const names = { name: { zh: '爱客美', en: 'Acme' } }
    expect(brandName(names, 'zh')).toBe('爱客美')
    expect(brandName(names, 'en')).toBe('Acme')
    expect(brandName(names, 'ja')).toBe('爱客美')
  })

  it('puts the name in the tab and its icon in place of the platform\'s', () => {
    const doc = document.implementation.createHTMLDocument('jabiz')
    const icon = doc.createElement('link')
    icon.rel = 'icon'
    icon.type = 'image/svg+xml'
    icon.href = '/favicon.svg'
    doc.head.appendChild(icon)
    applyBrandHead({ name: { en: 'Acme', zh: '爱客美' }, favicon: '/assets/acme.png' }, 'zh', doc)
    expect(doc.title).toBe('爱客美')
    expect(doc.querySelectorAll('link[rel~="icon"]')).toHaveLength(1)
    expect(icon.getAttribute('href')).toBe('/assets/acme.png')
    expect(icon.hasAttribute('type')).toBe(false)
    // Without a brand, the page keeps its own.
    const plain = document.implementation.createHTMLDocument('jabiz')
    applyBrandHead(undefined, 'en', plain)
    expect(plain.title).toBe('jabiz')
    // A page without an icon gets one.
    const bare = document.implementation.createHTMLDocument('x')
    applyBrandHead({ name: 'Acme', favicon: '/a.png' }, 'en', bare)
    expect(bare.querySelector('link[rel="icon"]')?.getAttribute('href')).toBe('/a.png')
  })
})
