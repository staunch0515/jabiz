-- Values of the database dictionaries (docs/culture/00-design.md section 3): editors can add more in the admin
-- (SysDictItem) without a release. Added as base data through the platform's jabiz_dict_put.
SELECT jabiz_dict_put('urn:jabiz:dict:culture:media-type', 'VIDEO', '{"zh": "视频", "ja": "動画", "en": "Video"}', 10, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:media-type', 'ARTICLE', '{"zh": "文章", "ja": "記事", "en": "Article"}', 20, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:media-type', 'PHOTO', '{"zh": "照片", "ja": "写真", "en": "Photo"}', 30, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:media-type', 'INTERVIEW', '{"zh": "访谈", "ja": "インタビュー", "en": "Interview"}', 40, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:media-type', 'AUDIO', '{"zh": "音频", "ja": "音声", "en": "Audio"}', 50, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:activity-type', 'CLASSROOM', '{"zh": "课堂活动", "ja": "授業活動", "en": "Classroom activity"}', 10, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:activity-type', 'DISCUSSION', '{"zh": "讨论活动", "ja": "ディスカッション", "en": "Discussion activity"}', 20, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:activity-type', 'REFLECTION', '{"zh": "反思活动", "ja": "振り返り", "en": "Reflection activity"}', 30, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:activity-type', 'LANGUAGE', '{"zh": "语言活动", "ja": "言語活動", "en": "Language activity"}', 40, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:age-group', '11-13', '{"zh": "11–13 岁", "ja": "11〜13歳", "en": "Ages 11–13"}', 10, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:age-group', '14-16', '{"zh": "14–16 岁", "ja": "14〜16歳", "en": "Ages 14–16"}', 20, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:age-group', '16-18', '{"zh": "16–18 岁", "ja": "16〜18歳", "en": "Ages 16–18"}', 30, true);
SELECT jabiz_dict_put('urn:jabiz:dict:culture:age-group', 'ADULT', '{"zh": "成人", "ja": "大人", "en": "Adults"}', 40, true);
