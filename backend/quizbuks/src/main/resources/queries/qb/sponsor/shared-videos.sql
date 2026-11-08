/*---
id: qb.sponsor.shared-videos
description: >-
  F-S12: the catalog of shared videos, for the publication wizard: the videos other sponsors share that their operator
  approved, with the provider's company, newest first; searched by title (any language) or provider, by topic and by
  orientation
entities: [QbSharedVideo, QbSponsor]
datasets:
  QbSharedVideo: urn:jabiz:dataset:default:QbSharedVideo
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  q:             { kind: { type: text, maxLength: 100 }, description: "Only videos whose title (any language) or provider contains this text" }
  topic:         { kind: { type: text, maxLength: 40 }, description: "Only videos with this topic (a code of the word list)" }
  vertical:      { kind: { type: bool }, description: "Only vertical (true) or horizontal (false) videos; both when not given" }
  sharedVideoId: { kind: { type: text, maxLength: 36 }, description: "Only this video (the one a draft picked), whatever its state" }
results:
  sharedVideoId:   { from: QbSharedVideo.sharedVideoId }
  providerId:      { from: QbSharedVideo.providerId }
  providerCompany: { from: QbSponsor.company }
  title:           { kind: { type: text } }
  url:             { from: QbSharedVideo.url }
  durationSec:     { from: QbSharedVideo.durationSec }
  vertical:        { from: QbSharedVideo.vertical }
  topics:          { from: QbSharedVideo.topics }
  status:          { from: QbSharedVideo.status }
  approvedAt:      { from: QbSharedVideo.approvedAt }
list:
  sorts:   [approvedAt]
  defaultSort: { field: approvedAt, asc: false }
  key: [sharedVideoId]
permissions: [qb.publication.write]
---*/
-- Only approved videos are in the catalog (SharedVideoRules.available); a draft's own pick is read by its key whatever
-- became of it, so that the wizard says it was withdrawn or taken down. A provider whose onboarding is gone (an erased
-- account) is not shown; the company is the onboarding's current one. The text is found as written: strpos takes it
-- literally. The topics are matched as the codes are stored (comma-separated, no blanks).
SELECT * FROM (
    SELECT
        v.{{QbSharedVideo.sharedVideoId}}  AS sharedVideoId,
        v.{{QbSharedVideo.providerId}}     AS providerId,
        (SELECT max(sp.{{QbSponsor.company}}) FROM {{QbSponsor}} sp
         WHERE sp.{{QbSponsor.userId}} = v.{{QbSharedVideo.providerId}}) AS providerCompany,
        CAST(v.{{QbSharedVideo.title}} AS text) AS title,
        v.{{QbSharedVideo.url}}            AS url,
        v.{{QbSharedVideo.durationSec}}    AS durationSec,
        v.{{QbSharedVideo.vertical}}       AS vertical,
        v.{{QbSharedVideo.topics}}         AS topics,
        v.{{QbSharedVideo.status}}         AS status,
        v.{{QbSharedVideo.approvedAt}}     AS approvedAt
    FROM {{QbSharedVideo}} v
    WHERE (CASE WHEN CAST(:sharedVideoId AS text) IS NULL THEN v.{{QbSharedVideo.status}} = 'APPROVED'
                ELSE v.{{QbSharedVideo.sharedVideoId}} = CAST(:sharedVideoId AS text) END)
      AND (CAST(:vertical AS boolean) IS NULL OR v.{{QbSharedVideo.vertical}} = CAST(:vertical AS boolean))
      AND (CAST(:topic AS text) IS NULL
           OR CAST(:topic AS text) = ANY(string_to_array(v.{{QbSharedVideo.topics}}, ',')))
) x
WHERE x.providerCompany IS NOT NULL
  AND (CAST(:q AS text) IS NULL
       OR strpos(lower(x.title), lower(CAST(:q AS text))) > 0
       OR strpos(lower(x.providerCompany), lower(CAST(:q AS text))) > 0)
