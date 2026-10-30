import { applyAppearance } from '@jabiz/ui'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import i18n from './i18n'
import './index.css'
import App from './App'
import { extension } from './extension'
import { registerExtensionMessages } from './extension/messages'
import { applyBrandHead } from './extension/brand'

registerExtensionMessages(i18n, extension)
// The application's name and icon in the browser's tab (phase 17v), in the language chosen.
applyBrandHead(extension.brand, i18n.language)
i18n.on('languageChanged', (language) => applyBrandHead(extension.brand, language))
// The remembered appearance before the first paint, so a dark choice does not flash light.
applyAppearance()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
