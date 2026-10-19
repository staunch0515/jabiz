import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '@jabiz/ui/testing'
import I18nTextInput from './I18nTextInput'

const params = { format: 'markdown' as const, multiline: true, maxLength: 100, required: ['en'], locales: ['zh', 'ja', 'en'] }

describe('I18nTextInput', () => {
  it('has a tab per language, marks the required ones and edits the texts', async () => {
    await i18n.changeLanguage('en')
    const onChange = vi.fn()
    render(<I18nTextInput name="title" params={params} value={{ zh: '你好' }} onChange={onChange} invalidLanguages={['en']} />)

    expect(screen.getByTestId('i18n-title-tab-zh').textContent).toBe('中文')
    expect(screen.getByTestId('i18n-title-tab-en').textContent).toContain('*')
    expect(screen.getByTestId('i18n-title-tab-ja').textContent).not.toContain('*')
    // The interface language is open first.
    fireEvent.change(screen.getByTestId('i18n-title-en'), { target: { value: 'Hello' } })
    expect(onChange).toHaveBeenCalledWith({ zh: '你好', en: 'Hello' })
    expect(screen.getByTestId('i18n-title-en').getAttribute('lang')).toBe('en')
  })

  it('previews Markdown with the display renderer, without raw HTML', async () => {
    await i18n.changeLanguage('en')
    render(<I18nTextInput name="title" params={params} value={{ en: '**Bold** <b onclick="x()">raw</b>' }} />)
    const preview = screen.getByTestId('markdown-view')
    expect(preview.querySelector('strong')?.textContent).toBe('Bold')
    expect(preview.querySelector('b')).toBeNull()
    expect(preview.getAttribute('lang')).toBe('en')
  })

  it('marks the languages with a problem and names each input by the field and its language', async () => {
    await i18n.changeLanguage('en')
    render(<I18nTextInput name="title" label="Title" params={params} value={{}} invalidLanguages={['en']} aria-describedby="problems" />)
    expect(screen.getByRole('tab', { name: /English/ })).toHaveAttribute('data-invalid', 'true')
    expect(screen.getByRole('tab', { name: /中文/ })).not.toHaveAttribute('data-invalid')
    const english = screen.getByLabelText('Title (English)')
    expect(english).toHaveAttribute('aria-invalid', 'true')
    expect(english).toHaveAttribute('aria-required', 'true')
    expect(english).toHaveAttribute('aria-describedby', 'problems')
    // Another language's tab shows its own input.
    fireEvent.mouseDown(screen.getByRole('tab', { name: /日本語/ }), { button: 0 })
    expect(await screen.findByLabelText('Title (日本語)')).toHaveAttribute('lang', 'ja')
    await expectAccessible()
  })

  it('has no preview for plain text', async () => {
    await i18n.changeLanguage('en')
    render(<I18nTextInput name="title" params={{ ...params, format: 'plain', multiline: false }} value={{ en: '**x**' }} />)
    expect(screen.queryByTestId('markdown-view')).toBeNull()
  })
})
