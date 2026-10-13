import { isValidElement, type ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { renderCell } from './columns'
import type { FieldMeta } from './types'

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, processOnly: false, operators: [], rules: [] }
const supplier = { ...base, name: 'supplierId', type: 'reference', targetEntity: 'Supplier' } as FieldMeta
const title = { ...base, name: 'title', type: 'custom', kindId: 'jabiz.i18n-text', locales: ['zh', 'ja', 'en'] } as unknown as FieldMeta

describe('cells', () => {
  it('show references by their label, else by their key', () => {
    const labels = { supplierId: { s1: 'Acme', s2: { zh: '甲', en: 'Alpha' } } }
    expect(renderCell(supplier, 's1', {}, i18n.t, 'en', { labels })).toBe('Acme')
    expect(renderCell(supplier, 's2', {}, i18n.t, 'en', { labels })).toBe('Alpha')
    expect(renderCell(supplier, 's3', {}, i18n.t, 'en', { labels })).toBe('s3')
    expect(renderCell(supplier, 's1', {}, i18n.t, 'en')).toBe('s1')
  })

  it('show multilingual texts in the best language, marking a fallback with lang', () => {
    expect(renderCell(title, { zh: '标题', en: 'Title' }, {}, i18n.t, 'zh')).toBe('标题')
    const fallback = renderCell(title, { en: 'Title' }, {}, i18n.t, 'ja', { defaultLocale: 'en' })
    expect(isValidElement(fallback)).toBe(true)
    const element = fallback as ReactElement<{ lang: string; children: string }>
    expect(element.props.lang).toBe('en')
    expect(element.props.children).toBe('Title')
    expect(renderCell(title, null, {}, i18n.t, 'en')).toBe('—')
  })
})
