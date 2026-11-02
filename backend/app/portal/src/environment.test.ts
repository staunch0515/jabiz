import { describe, expect, it } from 'vitest'
import { portalEnvironment } from './environment'

const staging = { name: 'staging', production: false }

describe('the portal\'s public host (phase 17ze)', () => {
  it('sets nothing without a public host', () => {
    expect(portalEnvironment(undefined)).toBeNull()
    expect(portalEnvironment('  ')).toBeNull()
  })

  it('shows the real system on the public host only', () => {
    const override = portalEnvironment('Shop.Example.com')!
    expect(override(staging, { hostname: 'shop.example.com' })).toEqual({ name: 'production', production: true })
    expect(override(staging, { hostname: 'staging.shop.example.com' })).toBeUndefined()
    expect(override(staging, { hostname: 'localhost' })).toBeUndefined()
  })
})
