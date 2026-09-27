import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import I18nTextInput from './I18nTextInput'

const params = { format: 'markdown' as const, multiline: true, maxLength: 100, required: ['en'], locales: ['zh', 'ja', 'en'] }

describe('I18nTextInput', () => {
  it('has a tab per language, marks the required ones and edits the texts', async () => {
    await i18n.changeLanguage('en')
    const onChange = vi.fn()
    render(<I18nTextInput params={params} value={{ zh: '你好' }} onChange={onChange} invalidLanguages={['en']} />)

    expect(screen.getByTestId('i18n-tab-zh').textContent).toBe('中文')
    expect(screen.getByTestId('i18n-tab-en').textContent).toContain('*')
    expect(screen.getByTestId('i18n-tab-ja').textContent).not.toContain('*')
    // The interface language is open first.
    fireEvent.change(screen.getByTestId('i18n-en'), { target: { value: 'Hello' } })
    expect(onChange).toHaveBeenCalledWith({ zh: '你好', en: 'Hello' })
    expect(screen.getByTestId('i18n-en').getAttribute('lang')).toBe('en')
  })

  it('previews Markdown with the display renderer, without raw HTML', async () => {
    await i18n.changeLanguage('en')
    render(<I18nTextInput params={params} value={{ en: '**Bold** <b onclick="x()">raw</b>' }} />)
    const preview = screen.getByTestId('markdown-view')
    expect(preview.querySelector('strong')?.textContent).toBe('Bold')
    expect(preview.querySelector('b')).toBeNull()
    expect(preview.getAttribute('lang')).toBe('en')
  })

  it('has no preview for plain text', async () => {
    await i18n.changeLanguage('en')
    render(<I18nTextInput params={{ ...params, format: 'plain', multiline: false }} value={{ en: '**x**' }} />)
    expect(screen.queryByTestId('markdown-view')).toBeNull()
  })
})
