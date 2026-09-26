# Culture, Unfiltered — Website Development Brief

> The client's original brief, kept verbatim as the source of requirements. The design that answers it is
> `00-design.md` (Chinese); section numbers there refer to the numbered sections below.

## 1. Project Overview

Culture, Unfiltered is a youth-led international digital ethnography project involving students from the United Kingdom, Poland, Sweden, Singapore, Japan, and Eswatini.

The purpose of the website is to document and explore how young people experience culture, identity, language, family, school, food, belonging, and everyday life across different cultural contexts.

The website should not present itself as an encyclopedia of six countries or claim that one participant represents an entire culture.

Instead, the central philosophy is:

**We document individual experiences, not entire cultures.**

Each participant is a "correspondent" who documents their own experiences through short videos, photographs, interviews, written reflections, and observations.

The website should feel like an interactive digital anthropology exhibition created by young people, rather than a conventional travel blog.

## 2. Target Audience

The primary audiences are:

- Teenagers and young adults interested in other cultures
- Students studying international/intercultural topics
- Teachers and schools
- Parents/community members
- People interested in anthropology and youth culture

The website should be accessible to someone who knows nothing about the project.

## 3. Overall Design Philosophy

The site should feel:

- modern
- youthful
- international
- editorial
- visually engaging
- academically credible
- curious rather than authoritative
- personal rather than corporate

Avoid making it look like:

- a tourism website
- a government/cultural organization website
- a corporate consulting website
- a stereotypical "Around the World" website

We want human stories rather than flags and tourist imagery to be the primary visual language.

The website should use a clean layout with plenty of whitespace, strong typography, photography/video, and subtle animations.

Flags and geographic information can be used for navigation, but they should not dominate the design.

## 4. Homepage

The homepage should immediately communicate the project's central idea.

Hero section:

**CULTURE, UNFILTERED**

Subtitle:

*Six teenagers. Six places. Hundreds of ways to experience culture.*

Countries:

🇬🇧 UK · 🇵🇱 Poland · 🇸🇪 Sweden · 🇸🇬 Singapore · 🇯🇵 Japan · 🇸🇿 Eswatini

Primary CTA: **EXPLORE THE STORIES**

Secondary CTA: **HOW WE WORK**

Then introduce the project's central question:

*Can one person represent an entire culture?*

Follow this with:

*Probably not. That's why we're not trying to.*

Then briefly explain that each participant documents their own experience rather than claiming to represent their country.

## 5. Main Navigation

The primary navigation should contain:

HOME · PEOPLE · THEMES · STORIES · OUR METHOD · RESOURCES

Potentially: ABOUT

The navigation should remain accessible throughout the site. On mobile, use a hamburger menu.

## 6. PEOPLE Page

This page introduces the six participants. Display six profile cards: UK, Poland, Sweden, Singapore, Japan, Eswatini.

Each card should contain:

- participant photograph/avatar
- name
- location
- short biography
- interests
- languages, if the participant wishes to disclose them
- link to their profile

Example:

> **Chloe** — Japan
> Interested in culture, identity, technology, and anthropology.
> View profile →

Each profile should have its own page.

## 7. Individual Participant Pages

Each participant should have a personal page containing:

- **Header**: Name + location + photograph
- **About**: Short biography.
- **"My Perspective"**: A short statement explaining that their content reflects their individual experience.
  Example: "I grew up across multiple cultural environments, which has made me interested in how people develop a sense of belonging."
- **Featured Stories**: Display their videos/articles.
- **Themes**: Show which themes they have contributed to.
- **Personal Reflection**: Optional written reflections about what they learned through participating in the project.

## 8. THEMES Page

This should be one of the most important sections of the website.

Rather than organizing everything only by country, organize content around shared anthropological questions.

Possible themes:

- 🏠 HOME — What makes somewhere feel like home?
- 🎒 SCHOOL — What does being a teenager look like in different societies?
- 🗣️ LANGUAGE — Does the language we speak influence how we see ourselves?
- 🍜 FOOD — What does everyday food tell us about culture?
- 👀 STEREOTYPES — What do outsiders get wrong about us?
- 👨‍👩‍👧 FAMILY — What does family mean to young people?
- 🌐 DIGITAL LIFE — How is globalization changing youth culture?

Clicking a theme should display contributions from all participating countries. For example:

HOME: Japan → video · Poland → video · Sweden → video · Singapore → video · UK → video · Eswatini → video

This allows users to compare perspectives directly.

## 9. STORIES Page

This is the multimedia content library. Stories can include:

- short documentaries
- vlogs
- interviews
- photographs
- written essays
- audio clips

Each story should have: title, contributor, location, theme, date, thumbnail, short description, video/article.

Example:

> **What Does Home Mean to Us?**
> A six-country exploration of what makes a place feel like home.
> Japan · Poland · Sweden · Singapore · UK · Eswatini
> WATCH STORY →

The stories should be filterable by: Country | Theme | Media Type

## 10. Story Page

Each story should have a clean editorial layout. Example:

> **What Does Home Mean to Us?**
> Theme: Home
> Contributors: Japan · Poland · Sweden · Singapore · UK · Eswatini
> [VIDEO PLAYER]

Then:

- **About this story**: Short explanation of the research question.
- **Perspectives**: Display each participant's contribution.
- **Reflection**: After watching the contributions, include a section asking: *What surprised us?* *What did we initially assume?* *What did we learn?*

This reflection component is important because the project is intended to be more than cultural entertainment.

## 11. OUR METHOD Page

This page explains the anthropological philosophy behind the project. Sections:

- **What is digital ethnography?** A short, accessible explanation.
- **Our approach**: We use observation, interviews, video, photography, personal reflection, cross-cultural discussion.
- **Reflexivity**: Explain that participants recognize that their own backgrounds influence what they notice and how they interpret other people's experiences.
- **Emic and Etic Perspectives**: Give a simple explanation of insider vs. outsider perspectives.
- **Cultural Representation**: Explain why the project avoids treating countries as culturally homogeneous. Core statement: *One person cannot represent an entire culture.*
- **Our Principles**: Display five principles: CONSENT · CONTEXT · RESPECT · REFLEXIVITY · RESPONSIBLE REPRESENTATION

## 12. RESOURCES Page

This section is intended to turn the project into an educational resource. Eventually we want to provide free materials for teachers and students. Examples:

- Classroom Activity — Can One Person Represent a Culture?
- Discussion Activity — Stereotype vs. Lived Experience
- Reflection Activity — What Does "Normal" Mean?
- Language Activity — Can Language Shape Identity?

Each resource could have: description, target age, estimated duration, downloadable PDF, related videos.

The resource section should be designed so additional resources can easily be added later.

## 13. Interactive Map

If technically feasible, include an interactive world map. The six participating locations should be clickable:
UK → Poland → Sweden → Singapore → Japan → Eswatini

Clicking a location displays: participant, stories, themes, videos, photographs.

The map should be a secondary navigation tool rather than the main way of organizing the project, because the project is intentionally about experiences rather than countries.

## 14. Search and Filtering

The site should eventually support:

- **Search** by title, participant, theme, keyword
- **Filters** — Country: UK, Poland, Sweden, Singapore, Japan, Eswatini · Theme: Home, School, Language, Food, Family, Identity, Stereotypes, Digital Life · Media: Video, Article, Photo, Interview, Audio

This will allow the website to scale as we add content.

## 15. Content Management

The most important technical requirement is that we should be able to add content without redesigning the website.

Ideally, the programmer should create a simple CMS or structured content system where we can add:

- **Story**: title, description, contributor, country, theme, date, thumbnail, video URL, article text, photographs, reflection
- **Participant**: name, country/location, photograph, biography, interests, languages, stories
- **Resource**: title, description, age group, duration, PDF/download, related stories

The website should automatically generate the relevant cards and pages from this information.

## 16. Video Hosting

We do NOT necessarily want to host large video files directly on the website. Use an external video platform such as YouTube or Vimeo and embed the videos. The website should display them seamlessly within the story pages.

## 17. Mobile Responsiveness

The website must work well on desktop, laptop, tablet, mobile. A significant portion of the audience will likely access the site through phones.

## 18. Accessibility

Please include: readable typography, sufficient contrast, alt text for images, captions/subtitles for videos, keyboard-accessible navigation, responsive design, clear heading hierarchy.

Because this is an educational project, accessibility should be treated as a core requirement rather than an optional feature.

## 19. Tone of the Website

The language should be: curious rather than authoritative, personal rather than generic, analytical rather than overly academic, respectful rather than exoticizing.

We want visitors to leave thinking: *"I never thought about my own culture that way."* rather than: *"I learned six facts about six countries."*

## 20. Long-Term Vision

The initial website will contain six participants. However, the architecture should allow the project to expand later. Potential future participants could come from: additional countries, different age groups, immigrant communities, international schools, youth organizations.

The website should therefore treat country, participant, theme, and story as separate pieces of data rather than hard-coding six countries into the site's structure.

The long-term vision is for Culture, Unfiltered to become a growing youth-led digital archive of lived cultural experiences.

## Core Design Principle

The most important idea for the programmer to understand is:

**This is not a website about six countries. It is a website about six young people asking questions about culture — and comparing what they discover.**

The website should make that distinction visible in its design, navigation, and content structure.
