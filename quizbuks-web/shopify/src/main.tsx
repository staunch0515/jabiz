import { configureClient } from '@jabiz/client'
import { applyAppearance } from '@jabiz/ui'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'
import './messages'

// The embedded pages' sign-in entry (jabizApp passes it as VITE_JABIZ_ENTRY; named here as well for `pnpm dev`):
// sessions of this tab only, signed in with Shopify's session token.
configureClient({ entry: 'shopify' })
applyAppearance()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
