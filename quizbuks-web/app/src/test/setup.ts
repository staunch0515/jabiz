import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'

// A loaded machine renders slowly: the default second for findBy/waitFor made tests flaky.
configure({ asyncUtilTimeout: 5000 })

afterEach(() => cleanup())

// Radix UI (the @jabiz/ui components) reads browser APIs jsdom does not provide.
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
window.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
Element.prototype.hasPointerCapture ??= () => false
Element.prototype.setPointerCapture ??= () => {}
Element.prototype.releasePointerCapture ??= () => {}
Element.prototype.scrollIntoView ??= () => {}
