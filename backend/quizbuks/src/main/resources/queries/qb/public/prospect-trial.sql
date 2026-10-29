/*---
id: qb.public.prospect-trial
description: A trial quiz a prospecting mail sent (Q48c), by the random key of its link
access: public
entities: [QbProspect]
datasets:
  QbProspect: urn:jabiz:dataset:public:QbProspect
params:
  key: { like: QbProspect.trialKey, required: true, description: The key in the mail's link }
results:
  trialKey: { from: QbProspect.trialKey }
  company:  { from: QbProspect.company }
  language: { from: QbProspect.language }
  trial:    { from: QbProspect.trial }
list:
  key: [trialKey]
---*/
-- Only what went out (the public dataset's scope); the key is random (24 bytes), so no other business's trial is
-- found by guessing.
SELECT p.{{QbProspect.trialKey}} AS trialKey, p.{{QbProspect.company}} AS company,
       p.{{QbProspect.language}} AS language, p.{{QbProspect.trial}} AS trial
FROM {{QbProspect}} p
WHERE p.{{QbProspect.trialKey}} = :key
