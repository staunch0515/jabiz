import { Button, DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuSeparator, DropdownMenuTrigger } from '@jabiz/ui'
import { ArrowDownUp, Download, Globe, Sparkles, Upload } from 'lucide-react'
import { createContext, useContext, useRef, useState, type ReactNode, type RefObject } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { AI_USAGE_QUERY } from '../ai/AiQuestionsPanel'
import { readAiUsage } from '../ai/api'
import { SPONSOR } from '../messages'
import { ProfileTransfer, type ProfileTransferControls } from './ProfileTransfer'
import { SponsorPackage, type SponsorPackageControls } from './SponsorPackage'

interface TransferTools {
  profile: RefObject<ProfileTransferControls | null>
  pack: RefObject<SponsorPackageControls | null>
  profileExporting: boolean
  /** What the sponsor package export is on, null when none. */
  packExporting: string | null
}

const Tools = createContext<TransferTools | null>(null)

/**
 * The profile package (Q100) and the sponsor package (F-C4) of the hub's overview (F-F3d): their dialogs and file
 * choices are mounted once here, the menu in the page's head ({@link TransferMenu}) and the notice of an import left
 * open (on the page) reach them through it.
 */
export function TransferTools({ children }: { children: ReactNode }) {
  const profile = useRef<ProfileTransferControls>(null)
  const pack = useRef<SponsorPackageControls>(null)
  const [profileExporting, setProfileExporting] = useState(false)
  const [packExporting, setPackExporting] = useState<string | null>(null)
  return (
    <Tools.Provider value={{ profile, pack, profileExporting, packExporting }}>
      <ProfileTransfer controls={profile} onExporting={setProfileExporting} />
      <SponsorPackage controls={pack} onExporting={setPackExporting} />
      {children}
    </Tools.Provider>
  )
}

/** Asks the sponsor package to give up the import left open; nothing when the overview has no {@link TransferTools}. */
export function useAbandonImport() {
  const tools = useContext(Tools)
  return tools ? (importId: string) => tools.pack.current?.askAbandon(importId) : undefined
}

/**
 * "Import and export" in the head of the overview: the profile package (export, import, the prompt for an AI tool)
 * and the sponsor package (export, import). The items keep the ids the buttons of the two cards had.
 */
export function TransferMenu() {
  const { t } = useTranslation(SPONSOR)
  const tools = useContext(Tools)
  // F-C7: only where the deployment has drafting from a website on.
  const usage = useQuery({ queryKey: AI_USAGE_QUERY, queryFn: () => readAiUsage(), enabled: tools !== null })
  if (!tools) return null
  const { profile, pack, profileExporting, packExporting } = tools
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" data-testid="profile-transfer">
          <ArrowDownUp aria-hidden />
          {t('profile.transfer.menu')}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-64" data-testid="profile-transfer-menu">
        <DropdownMenuGroup>
          <DropdownMenuLabel>{t('profile.transfer.title')}</DropdownMenuLabel>
          <DropdownMenuItem disabled={profileExporting} data-testid="profile-export"
            onSelect={() => profile.current?.exportNow()}>
            <Download aria-hidden />
            {profileExporting ? t('profile.transfer.exporting') : t('profile.transfer.export')}
          </DropdownMenuItem>
          <DropdownMenuItem data-testid="profile-import" onSelect={() => profile.current?.pickFile()}>
            <Upload aria-hidden />
            {t('profile.transfer.import')}
          </DropdownMenuItem>
          {usage.data?.siteProfileEnabled && (
            <DropdownMenuItem data-testid="profile-site" onSelect={() => profile.current?.openSite()}>
              <Globe aria-hidden />
              {t('site.menu')}
            </DropdownMenuItem>
          )}
          <DropdownMenuItem data-testid="profile-prompt" onSelect={() => profile.current?.openPrompt()}>
            <Sparkles aria-hidden />
            {t('profile.transfer.prompt.open')}
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuLabel>{t('package.title')}</DropdownMenuLabel>
          <DropdownMenuItem disabled={packExporting !== null} data-testid="sponsor-package-export"
            onSelect={() => pack.current?.exportNow()}>
            <Download aria-hidden />
            {packExporting ? t('package.exporting', { what: packExporting }) : t('package.export')}
          </DropdownMenuItem>
          <DropdownMenuItem data-testid="sponsor-package-import" onSelect={() => pack.current?.pickFile()}>
            <Upload aria-hidden />
            {t('package.import')}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
