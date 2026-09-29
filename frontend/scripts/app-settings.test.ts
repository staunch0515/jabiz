// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { appSettingsProblems } from './app-settings.ts'

describe('appSettingsProblems', () => {
  it('accepts no settings and valid ones', () => {
    expect(appSettingsProblems({})).toEqual([])
    expect(appSettingsProblems({ VITE_JABIZ_LANGUAGES: 'en', VITE_JABIZ_REGION: 'en-US' })).toEqual([])
    expect(appSettingsProblems({ VITE_JABIZ_LANGUAGES: '', VITE_JABIZ_REGION: '' })).toEqual([])
  })

  it('reports every problem', () => {
    expect(appSettingsProblems({ VITE_JABIZ_LANGUAGES: 'en,fr', VITE_JABIZ_REGION: 'english' })).toEqual([
      'VITE_JABIZ_LANGUAGES: fr not platform languages',
      'VITE_JABIZ_REGION must look like en-US, was "english"',
    ])
    expect(appSettingsProblems({ VITE_JABIZ_LANGUAGES: ' , ' })).toEqual(['VITE_JABIZ_LANGUAGES names no language'])
    expect(appSettingsProblems({ VITE_JABIZ_LANGUAGES: ',x' })).toEqual(['VITE_JABIZ_LANGUAGES: x not platform languages'])
  })
})
