import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

afterEach(async () => {
  cleanup()
  // A call left unanswered in one test (jsdom has no server) must not leave the next one behind the maintenance notice
  // (17x). Imported only now: imported here first, the client would be loaded before a test file's mocks of its parts.
  const client: { resetServiceAvailability?: () => void } = await import('@jabiz/client')
  client.resetServiceAvailability?.()
})

// The appearance (prefers-color-scheme) and Radix UI read these browser APIs, which jsdom does not provide. (Tests of
// build scripts run without a DOM.)
if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })

  // Radix UI (the @jabiz/ui components) measures and captures the pointer with APIs jsdom does not have.
  window.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Element.prototype.hasPointerCapture ??= () => false
  Element.prototype.setPointerCapture ??= () => {}
  Element.prototype.releasePointerCapture ??= () => {}
  Element.prototype.scrollIntoView ??= () => {}
}
