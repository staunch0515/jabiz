import { configureClient } from '@jabiz/client'
import { applyAppearance } from '@jabiz/ui'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'
import './messages'
import './qb/kudos'

// The sponsor console's sign-in entry (jabizApp passes it as VITE_JABIZ_ENTRY; named here as well for `pnpm dev`):
// its sessions (this tab only, locked after 30 minutes idle) are the console's, apart from the app's and the
// administration's in the same browser.
configureClient({ entry: 'sponsor' })
// The remembered appearance before the first paint.
applyAppearance()
// Not a PWA (jabizApp's pwa = OFF): no Service Worker. The app's worker at / leaves /sponsor/ alone.

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
