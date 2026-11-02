import { describe, expect, it } from 'vitest'
import { bannerEnvironment } from './banner'

describe('the test-system banner on the brand\'s own site', () => {
  const site = 'https://quizbuks.com'

  it('is never shown on the brand\'s site or its www, whatever the server says', () => {
    expect(bannerEnvironment('quizbuks.com', site)).toEqual({ name: 'production', production: true })
    expect(bannerEnvironment('www.quizbuks.com', site)).toEqual({ name: 'production', production: true })
    expect(bannerEnvironment('QuizBuks.com', site)).toEqual({ name: 'production', production: true })
    expect(bannerEnvironment('quizbuks.com', 'https://www.quizbuks.com/')).toEqual({ name: 'production',
      production: true })
  })

  it('follows the server everywhere else: staging, local, the tests, look-alike hosts', () => {
    expect(bannerEnvironment('staging.quizbuks.com', site)).toBeUndefined()
    expect(bannerEnvironment('localhost', site)).toBeUndefined()
    expect(bannerEnvironment('quizbuks.com.example.org', site)).toBeUndefined()
    expect(bannerEnvironment('notquizbuks.com', site)).toBeUndefined()
  })

  it('follows the server when the brand has no usable site address', () => {
    expect(bannerEnvironment('quizbuks.com', '')).toBeUndefined()
    expect(bannerEnvironment('quizbuks.com', 'not a url')).toBeUndefined()
  })
})
