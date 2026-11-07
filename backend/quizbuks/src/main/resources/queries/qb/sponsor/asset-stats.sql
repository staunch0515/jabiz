/*---
id: qb.sponsor.asset-stats
description: >-
  The size and the places of each of the sponsor's assets (F-C2), for sorting the library by size and finding the
  unused ones: the bytes of its file (as uploaded; links to outside videos and web links have none), the bytes of its
  texts, and how many places it was copied to
entities: [QbAsset, QbAssetUse, SysFile]
datasets:
  QbAsset: urn:jabiz:dataset:me:QbAsset
  QbAssetUse: urn:jabiz:dataset:me:QbAssetUse
  SysFile: urn:jabiz:dataset:platform:SysFile
results:
  assetId:   { from: QbAsset.assetId }
  fileBytes: { kind: { type: numeric, precision: 19, scale: 0 } }
  textBytes: { kind: { type: numeric, precision: 19, scale: 0 } }
  uses:      { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [fileBytes, uses]
  defaultSort: { field: fileBytes, asc: false }
  key: [assetId]
permissions: [qb.content.write]
---*/
-- Only the sponsor's own assets (me:), and only the files they name (a library takes the sponsor's own uploads only,
-- AssetProcesses): no other file is read. The text bytes are those of the snapshot's assets.text-bytes, per asset.
SELECT
    a.{{QbAsset.assetId}} AS assetId,
    CAST(coalesce(fi.{{SysFile.sizeBytes}}, 0) + coalesce(fp.{{SysFile.sizeBytes}}, 0)
        + coalesce(fa.{{SysFile.sizeBytes}}, 0) AS numeric(19, 0)) AS fileBytes,
    CAST(coalesce(octet_length(a.{{QbAsset.name}}), 0)
        + coalesce(octet_length(a.{{QbAsset.description}}), 0)
        + coalesce(octet_length(a.{{QbAsset.body}}), 0)
        + coalesce(octet_length(a.{{QbAsset.prizeDescription}}), 0)
        + coalesce((SELECT sum(octet_length(t.value ->> 'text'))
                    FROM jsonb_each(CASE WHEN a.{{QbAsset.translations}} IS NULL THEN CAST('{}' AS jsonb)
                                         ELSE CAST(a.{{QbAsset.translations}} AS jsonb) END) l
                    CROSS JOIN LATERAL jsonb_each(CASE WHEN jsonb_typeof(l.value) = 'object' THEN l.value
                                                       ELSE CAST('{}' AS jsonb) END) t
                    WHERE jsonb_typeof(t.value) = 'object'), 0) AS numeric(19, 0)) AS textBytes,
    CAST((SELECT count(*) FROM {{QbAssetUse}} u WHERE u.{{QbAssetUse.assetId}} = a.{{QbAsset.assetId}})
        AS numeric(9, 0)) AS uses
FROM {{QbAsset}} a
LEFT JOIN {{SysFile}} fi ON fi.{{SysFile.fileId}} = a.{{QbAsset.image}}
LEFT JOIN {{SysFile}} fp ON fp.{{SysFile.fileId}} = a.{{QbAsset.pdf}}
LEFT JOIN {{SysFile}} fa ON fa.{{SysFile.fileId}} = a.{{QbAsset.audio}}
