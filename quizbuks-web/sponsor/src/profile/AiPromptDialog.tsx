import { Button, Checkbox, Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, Input,
  Label, notify, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Textarea } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { Copy } from 'lucide-react'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { readMySponsorPage } from '../page/api'
import { readProductLimit } from '../products/api'
import { PRODUCT_LIMIT_QUERY } from '../products/ProductsPage'
import { CONTENT_LANGUAGES, contentLanguageOf, readMySponsor, type ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'
import { DEFAULT_MAX_PRODUCTS, profilePrompt } from './aiPrompt'
import { copyText } from './copyText'

/**
 * F-C5: the prompt for another AI tool that writes a profile package from the sponsor's website
 * (docs/quizbuks/profile-package-prompts.md). The sponsor types the parameters, the page fills them in and copies the
 * whole text; the rules in it are the server's (ProfilePackagePromptsTest). The contact name is the sponsor's own
 * (the AI never looks for a person on the website, Q104 P5).
 */
export function AiPromptDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const sponsor = useQuery({ queryKey: ['qb', 'sponsor-for-prompt'], queryFn: readMySponsor, enabled: open })
  const page = useQuery({ queryKey: ['qb', 'page-for-prompt'], queryFn: readMySponsorPage, enabled: open })
  const limit = useQuery({ queryKey: PRODUCT_LIMIT_QUERY, queryFn: readProductLimit, enabled: open })
  // What the sponsor typed; until then what the server knows about them.
  const [typed, setTyped] = useState<{ website?: string; contactName?: string }>({})
  const [baseLanguage, setBaseLanguage] = useState<ContentLanguage>(contentLanguageOf(i18n.language))
  const [others, setOthers] = useState<ContentLanguage[]>([])
  const [withProducts, setWithProducts] = useState(true)
  const website = typed.website ?? page.data?.website ?? ''
  const contactName = typed.contactName ?? sponsor.data?.contactName ?? ''
  const prompt = useMemo(() => profilePrompt({ website, contactName, baseLanguage,
    otherLanguages: others.filter((language) => language !== baseLanguage), withProducts,
    maxProducts: limit.data ?? DEFAULT_MAX_PRODUCTS }), [website, contactName, baseLanguage, others, withProducts, limit.data])
  const ready = website.trim() !== '' && contactName.trim() !== ''

  const copy = async () => {
    if (await copyText(prompt)) notify.success(t('profile.transfer.prompt.copied'))
    else notify.error(t('profile.transfer.prompt.copyFailed'))
  }
  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) onClose() }}>
      <DialogContent className="sm:max-w-2xl" data-testid="profile-prompt-dialog">
        <DialogHeader>
          <DialogTitle>{t('profile.transfer.prompt.title')}</DialogTitle>
          <DialogDescription>{t('profile.transfer.prompt.hint')}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-website`}>{t('profile.transfer.prompt.website')}</Label>
            <Input id={`${id}-website`} type="url" inputMode="url" placeholder="https://" value={website}
              data-testid="profile-prompt-website" onChange={(event) => setTyped({ ...typed, website: event.target.value })} />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-contact`}>{t('profile.transfer.prompt.contact')}</Label>
            <Input id={`${id}-contact`} value={contactName} aria-describedby={`${id}-contact-hint`}
              data-testid="profile-prompt-contact" onChange={(event) => setTyped({ ...typed, contactName: event.target.value })} />
            <p id={`${id}-contact-hint`} className="text-muted-foreground text-sm">{t('profile.transfer.prompt.contactHint')}</p>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-base`}>{t('profile.transfer.prompt.base')}</Label>
            <Select value={baseLanguage} onValueChange={(next) => setBaseLanguage(next as ContentLanguage)}>
              <SelectTrigger id={`${id}-base`} className="w-48" data-testid="profile-prompt-base"><SelectValue /></SelectTrigger>
              <SelectContent>
                {CONTENT_LANGUAGES.map((language) => (
                  <SelectItem key={language} value={language}>{tQb(`languages.${language}`)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <fieldset className="flex flex-col gap-1">
            <legend className="text-sm font-medium">{t('profile.transfer.prompt.others')}</legend>
            <div className="flex flex-wrap gap-4">
              {CONTENT_LANGUAGES.filter((language) => language !== baseLanguage).map((language) => (
                <span key={language} className="flex items-center gap-2">
                  <Checkbox id={`${id}-other-${language}`} checked={others.includes(language)}
                    data-testid={`profile-prompt-other-${language}`}
                    onCheckedChange={(checked) => setOthers(checked === true
                      ? [...others, language] : others.filter((other) => other !== language))} />
                  <Label htmlFor={`${id}-other-${language}`}>{tQb(`languages.${language}`)}</Label>
                </span>
              ))}
            </div>
          </fieldset>
          <span className="flex items-center gap-2">
            <Checkbox id={`${id}-products`} checked={withProducts} data-testid="profile-prompt-products"
              onCheckedChange={(checked) => setWithProducts(checked === true)} />
            <Label htmlFor={`${id}-products`}>
              {t('profile.transfer.prompt.products', { max: limit.data ?? DEFAULT_MAX_PRODUCTS })}
            </Label>
          </span>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-text`}>{t('profile.transfer.prompt.text')}</Label>
            <Textarea id={`${id}-text`} readOnly rows={8} value={prompt} className="font-mono text-xs"
              data-testid="profile-prompt-text" onFocus={(event) => event.currentTarget.select()} />
            {!ready && <p className="text-muted-foreground text-sm" role="status">{t('profile.transfer.prompt.missing')}</p>}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('profile.transfer.prompt.close')}</Button>
          <Button disabled={!ready} data-testid="profile-prompt-copy" onClick={() => void copy()}>
            <Copy aria-hidden />{t('profile.transfer.prompt.copy')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
