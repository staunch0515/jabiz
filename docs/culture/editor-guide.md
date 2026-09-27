# Culture, Unfiltered — Editor guide

This guide is for the people who run the project in the admin at `/admin/`: the project lead (**administrator**),
editors and teachers (**curators**), and the young people themselves (**correspondents**). Everything described here is
done in the admin pages; nobody needs to write code.

Two rules come before everything else, because most participants are under 18:

- **No consent, no publication.** Nothing about a participant is public without a valid consent record: their own, and
  (while the switch says so) their guardian's for anyone under 18. Withdrawing consent takes their content offline at
  once.
- **Keep people findable only as the project intends.** First names or chosen names only, never surnames, schools,
  streets or exact locations. Account names are pseudonyms (see below).

## 1. Before you start (administrator, once)

1. Sign in as the first administrator (its password is printed in the application log on the first start).
2. Open **Processes** and run **CULTURE_SETUP**. It creates:
   - the roles **CURATOR** and **CORRESPONDENT** with their permissions,
   - the menu (Content, People, Settings, My content),
   - the two switches (section 8).

   Running it again changes nothing.
3. Create an account for every curator and correspondent (**Processes → USER_CREATE**), then give them their role in
   **SecUserRole**.

**Account names are pseudonyms**, for example `cu-jp-01` for the first correspondent in Japan. The name of an account
stays forever in the records of who did what, which cannot be deleted, so it must never be a real name. The same
pseudonym goes into the participant's **Login account** field, which is how the system knows whose content is whose.

## 2. Places and themes

- **Places** are where the participants speak from.
  - The **slug** is used in addresses and filters (`japan`, `polish-community-london`).
  - The **name** is shown on the site.
  - The optional **country code** (two capital letters, `JP`) shows a small flag.
  - **Area** says "Tokyo area", never a school or a street. **Latitude/longitude** are the centre of the city or
    country, for the map.

  A place does not have to be a country.
- **Themes** are the shared questions (HOME — *What makes somewhere feel like home?*). The icon is decoration. Untick
  **Visible** to hide a place or theme from the site.

Every text field that can be translated has one tab per language (English, Chinese, Japanese). English is the one the
site falls back to, so fill it in first; Markdown fields show a preview.

## 3. Participants and consent

1. **People → Participants → New**. Fill in:
   - **Name**: a first name or a chosen name only.
   - **Place**.
   - **Adult**: tick only when they are 18 or over.
   - **Login account**: their pseudonymous account name.

   A portrait is optional (an illustration or avatar works too); if you add one, write an English text alternative.
   Leave **Languages** empty unless they want them shown. The participant starts as a **Draft**.
2. **People → Consent records → New**, one record per signed form:
   - **Given by**: the participant themselves, or their guardian.
   - What the consent covers: photos, video, voice recordings. Text and the name are covered by any consent.
   - The **signing date**.
   - The scanned **signed form**. Only people with the consent permission can ever open it; it is never public.

   Consent records cannot be edited. To correct one, withdraw it (section 7) and record a new one.
3. On the participant's row, run **Activate**. It checks that the participant has consent:
   - their own consent is always needed;
   - a guardian's consent is also needed for anyone under 18, while that switch is on;
   - if they have a portrait, the consent must cover photos.

   **Hide** takes a profile off the site; **Activate** brings it back.

## 4. Photos and audio

Upload from the form of the story, perspective, participant or photo. The system:

- removes location (GPS) and other hidden data from photos, and makes smaller versions for the site;
- accepts JPEG and PNG photos up to 15 MB;
- accepts MP3, M4A and OGG audio up to 30 MB;
- accepts PDF resources up to 30 MB.

Videos are **not** uploaded. They stay on YouTube or Vimeo. Enter the platform and the video's **id**, not its address.
For `https://www.youtube.com/watch?v=abc123XYZ` the id is `abc123XYZ`. Then tick **Captions confirmed** once you have
checked that the video has accurate captions.

For every photo:

- write an English **text alternative** describing what matters in it, for people who cannot see it;
- tick **Shows people who can be recognised** if anyone other than the correspondent can be recognised (family,
  friends, classmates). Tick **Their consent is confirmed** only once you know they (or their guardian) agreed.

## 5. Stories and perspectives

A **story** answers one question. Each participant's part in it is a **perspective**: their text, video or audio, and
their photos.

**A correspondent's own story**

1. The correspondent opens **My content → My drafts → New**. The story is theirs automatically.
2. They add their perspective in **My perspectives** and their photos in **My photos**, choosing the story.
3. A curator links the story to its themes (**Content → Stories**, open the story, theme list).
4. The correspondent runs **Submit** on the story.
   - From then on they can see it, but can no longer change it or its perspectives and photos.
   - When review is required (the default), it waits for a curator.

**A story from several people** (for example, HOME from six places)

1. A curator creates the story in **Content → Stories** and links its themes.
2. Each correspondent adds their own perspective and photos to it from **My content**. A curator can also enter a
   perspective for a participant who has no account.

**Review**

- **Return** sends a story in review back to its correspondent with a note. They can edit it again, then submit it
  again. The note is stored with the story only; it is left out of the operation records.
- **Publish** runs the publish check (section 6).
  - If anything fails, nothing is published, and every problem is listed at once.
  - If everything passes, the story, all its perspectives and photos, and its theme links go public.
  - Running **Publish** again on a published story checks it again and publishes what was added since.
- **Unpublish** takes a story and all its parts offline. **Reopen** makes an unpublished story a draft again, editable
  by its correspondents.

## 6. What the publish check messages mean

| Message code | What to do |
|---|---|
| `ENGLISH_REQUIRED` | Fill in the English tab of the named field (title, summary or thumbnail text alternative). |
| `THUMBNAIL_REQUIRED` | Upload a thumbnail for the story. |
| `THEME_REQUIRED` | Link the story to at least one theme. |
| `BODY_REQUIRED` | An article or interview needs text: in the story's text or in a perspective. |
| `VIDEO_REQUIRED` | A video story needs a video: the story's own or a perspective's. |
| `STORY_EMPTY` | Add a perspective or the story's text. |
| `CAPTIONS_NOT_CONFIRMED` | Check the video's captions, then tick **Captions confirmed** on the story or perspective named. |
| `TRANSCRIPT_REQUIRED` | Every audio recording needs a transcript (on its perspective, or on the story for story-level audio). |
| `ALT_TEXT_REQUIRED` | Write the English text alternative of the photo named. |
| `PEOPLE_CONSENT_NOT_CONFIRMED` | Confirm that the people who can be recognised in the photo agreed, or remove the photo. |
| `MEDIA_STORY_MISMATCH` | The photo belongs to a perspective of another story: move it or delete it. |
| `PARTICIPANT_NOT_ACTIVE` | The perspective's participant is not active: activate them first (section 3). |
| `CONSENT_MISSING` | The participant's consent is missing or does not cover what is published. The message lists what is missing: `PARTICIPANT` (their own consent), `GUARDIAN`, or a kind of media (`PHOTO`, `VIDEO`, `VOICE`). Record the consent, or remove that media. |
| `CONTRIBUTION_OWNER_MISMATCH` | A perspective or photo was added by a correspondent other than its participant. Check with them and correct it. |
| `WRONG_STATE` | The action does not apply now (for example, returning a story that is not in review). |

## 7. Withdrawing consent and erasing

**Withdraw.** On the consent record, run **Withdraw**.

- If the participant's remaining consents no longer cover what of theirs is public, the participant becomes **Consent
  withdrawn**, and every published story with a perspective of theirs is unpublished in the same step. Stories they
  are only in as a draft stay drafts.
- If the remaining consents still cover everything, nothing else changes. For example, withdrawing a duplicate record
  changes nothing else.

Once the public site is live (stage C2), their photos and files stop being served at once in the site's pages.
Someone who had already opened a file's address may still load it for up to about six minutes (one minute of server
cache plus five minutes of browser cache).

**Erase.** On a participant whose consent was withdrawn, the administrator can run **Erase**. In one step it
permanently deletes:

- their perspectives and the photos and audio of those perspectives,
- their portrait,
- their consent records and the scanned forms,
- the participant record.

This cannot be undone. Stories stay; a story left without perspectives fails its next publish check. Then disable
their login account (accounts are only disabled, never deleted; the name was a pseudonym anyway).

To bring a withdrawn participant back instead, record a new consent, run **Reopen** (the profile becomes a draft) and
**Activate**.

## 8. Switches (administrator)

**Settings → Switches** shows the two switches. The administrator changes them with the **PARAM_SET** process, or
schedules a change with **PARAM_SCHEDULE**. Every change is kept with who made it and when.

| Switch | Default | Meaning |
|---|---|---|
| `culture.review.required` | on | A correspondent's submitted story waits for a curator. When off, submitting publishes it at once, and the publish check still applies. |
| `culture.consent.guardian.required` | on | Participants under 18 need a guardian's consent record as well as their own. Their own consent is always required. |

## 9. Site texts and resources

- **Content → Site texts** holds the fixed texts of the home, method and about pages (the key says where each
  appears). English was filled in from the project brief; add Chinese and Japanese in their tabs. The site falls back to
  English meanwhile.
- **Content → Resources** are teaching materials: description, age group, duration, an optional PDF and the activity
  itself (readable on the page, not only as a PDF). **Publish** checks for the English description, age group and
  duration. Related stories are linked in the resource's list of stories; only published stories ever show.
