import { expect, LOCALES, seedWorld, settled, test, type World } from './support'

/**
 * Nothing about places, people, themes or stories is written in the site's code (docs/culture/ROADMAP.md, C3):
 * a new place, participant, theme and story added through the admin appear on every page they belong to.
 */
test.describe.configure({ mode: 'serial' })

let w: World

test.beforeAll(async ({ request, browser }) => {
  w = await seedWorld(request, browser)
})

test('a new place, person, theme and story appear on the home page', async ({ page }) => {
  await page.goto('/en/')
  await settled(page)
  const places = page.getByRole('list', { name: 'Places' })
  await expect(places.getByRole('link', { name: w.placeName, exact: true })).toHaveAttribute('href', `/en/stories?place=${w.place}`)
  await expect(page.getByRole('link', { name: new RegExp(w.themeQuestion.replace(/[()?]/g, '.')) })).toHaveAttribute(
    'href',
    `/en/themes/${w.theme}`,
  )
  await expect(page.getByRole('heading', { name: w.personName, level: 3 })).toBeVisible()
})

test('the person has a card and a page with their stories and themes', async ({ page }) => {
  await page.goto('/en/people')
  await page.getByRole('link', { name: w.personName, exact: true }).click()
  await expect(page).toHaveURL(`/en/people/${w.person}`)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(w.personName)
  await expect(page.getByText(w.placeName).first()).toBeVisible()
  await expect(page.getByRole('heading', { name: 'My perspective' })).toBeVisible()
  await expect(page.getByText('English, Te reo Māori')).toBeVisible()
  await expect(page.getByRole('link', { name: w.storyTitle })).toHaveAttribute('href', `/en/stories/${w.story}`)
  await expect(page.getByRole('link', { name: w.themeTitle })).toHaveAttribute('href', `/en/themes/${w.theme}`)
  await expect(page.getByRole('img', { name: `${w.personName} on a harbour wall` })).toBeVisible()
})

test('the theme page compares the new perspective, under its place', async ({ page }) => {
  await page.goto('/en/themes')
  await page.getByRole('link', { name: new RegExp(w.themeTitle) }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(w.themeQuestion)
  const perspective = page.getByRole('article', { name: w.perspectiveHeading })
  await expect(perspective.getByRole('link', { name: w.personName })).toBeVisible()
  await expect(perspective.getByText(w.placeName)).toBeVisible()
  await perspective.getByRole('link', { name: /Read in the story/ }).click()
  await expect(page).toHaveURL(new RegExp(`/en/stories/${w.story}#p-`))
  await expect(page.getByRole('heading', { name: w.perspectiveHeading, level: 3 })).toBeFocused()
})

test('the story archive filters by the new place and theme, and the story page has everything', async ({ page }) => {
  await page.goto('/en/stories')
  await page.getByRole('checkbox', { name: w.placeName }).click()
  await expect(page.getByRole('checkbox', { name: w.placeName })).toBeChecked()
  await expect(page).toHaveURL(`/en/stories?place=${w.place}`)
  await expect(page.getByRole('search').getByRole('status')).toHaveText('1 story')
  await page.getByRole('checkbox', { name: w.themeTitle }).click()
  await expect(page.getByRole('checkbox', { name: w.themeTitle })).toBeChecked()
  await expect(page.getByRole('search').getByRole('status')).toHaveText('1 story')
  await page.getByRole('link', { name: w.storyTitle }).click()

  await expect(page.getByRole('heading', { level: 1 })).toHaveText(w.storyTitle)
  await expect(page.getByRole('link', { name: w.themeTitle })).toBeVisible()
  await expect(page.getByRole('list', { name: 'Correspondents' }).getByRole('link', { name: new RegExp(w.personName) })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'About this story' })).toBeVisible()
  await expect(page.getByRole('heading', { name: w.perspectiveHeading })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'What surprised us?' })).toBeVisible()
  await expect(page.getByRole('button', { name: `Play video: ${w.storyTitle}` })).toBeVisible()
})

test('the resource lists the new story', async ({ page }) => {
  await page.goto('/en/resources')
  await page.getByRole('link', { name: w.resourceTitle }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(w.resourceTitle)
  await expect(page.getByText('45 minutes')).toBeVisible()
  await expect(page.getByRole('link', { name: w.storyTitle })).toBeVisible()
})

test('content without a translation falls back to English, marked as English', async ({ page }) => {
  for (const locale of LOCALES) {
    await page.goto(`/${locale}/stories/${w.story}`)
    await settled(page)
    await expect(page.locator('html')).toHaveAttribute('lang', locale)
    const h1 = page.getByRole('heading', { level: 1 })
    await expect(h1).toHaveText(w.storyTitle)
    if (locale === 'en') await expect(h1).not.toHaveAttribute('lang')
    else await expect(h1).toHaveAttribute('lang', 'en')
  }
  // The place has a Japanese name.
  await page.goto(`/ja/people/${w.person}`)
  await expect(page.getByText(/港町/).first()).toBeVisible()
})

test('a page that is not public is "not found"', async ({ page }) => {
  await page.goto('/en/stories/no-such-story')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('We could not find this page')
  await page.goto('/xx/')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('We could not find this page')
})
