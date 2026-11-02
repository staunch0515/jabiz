import { describe, expect, it } from 'vitest'
import { siteEnvironment } from './environment'

const staging = { name: 'staging', production: false }

describe('the test-system banner on the brand\'s own site (platform 17ze)', () => {
  const override = siteEnvironment('https://quizbuks.com')!
  const at = (hostname: string) => override(staging, { hostname })

  it('is never shown on the brand\'s site or its www, whatever the server says', () => {
    expect(at('quizbuks.com')).toEqual({ name: 'production', production: true })
    expect(at('www.quizbuks.com')).toEqual({ name: 'production', production: true })
    expect(at('QuizBuks.com')).toEqual({ name: 'production', production: true })
    expect(siteEnvironment('https://www.quizbuks.com/')!(staging, { hostname: 'quizbuks.com' }))
      .toEqual({ name: 'production', production: true })
  })

  it('follows the server everywhere else: staging, local, the tests, look-alike hosts', () => {
    expect(at('staging.quizbuks.com')).toBeUndefined()
    expect(at('localhost')).toBeUndefined()
    expect(at('quizbuks.com.example.org')).toBeUndefined()
    expect(at('notquizbuks.com')).toBeUndefined()
  })

  it('sets nothing when the brand has no usable site address', () => {
    expect(siteEnvironment('')).toBeNull()
    expect(siteEnvironment('not a url')).toBeNull()
  })
})
