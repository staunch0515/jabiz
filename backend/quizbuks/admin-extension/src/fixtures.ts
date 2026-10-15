/**
 * Data of the extension's tests. `SNAPSHOT_JSON` is a version's `content` as `content.QuizSnapshots` writes it
 * (schema 1, canonical JSON: keys in order, every part numbered), with a material of every kind, an option that is
 * only an image, and an introduction with a link the page must not make live.
 */
export const COVER = '0192f0a0-0000-7000-8000-00000000c0e1'
export const PICTURE = '0192f0a0-0000-7000-8000-00000000b1c7'
export const OPTION_IMAGE = '0192f0a0-0000-7000-8000-0000000097a1'
export const PDF = '0192f0a0-0000-7000-8000-0000000000df'
export const AUDIO = '0192f0a0-0000-7000-8000-00000000a0d1'

export const SNAPSHOT_JSON =
  '{"aiGenerated":false,"cover":"' + COVER + '",' +
  '"intro":"Read the **notes** first. [Click me](javascript:alert(1)) <script>alert(2)</script>",' +
  '"materials":[' +
  '{"audio":null,"body":"# Notes\\n\\nParis is the capital of France.","description":"Everything you need.",' +
  '"images":[],"kind":"ARTICLE","no":1,"pdf":null,"title":"Read first","url":null},' +
  '{"audio":null,"body":null,"description":null,"images":[],"kind":"VIDEO_LINK","no":2,"pdf":null,' +
  '"title":"Watch","url":"https://video.example/watch?v=1"},' +
  '{"audio":null,"body":null,"description":null,"images":[],"kind":"LINK","no":3,"pdf":null,' +
  '"title":"Not a web link","url":"javascript:alert(3)"},' +
  '{"audio":null,"body":null,"description":null,"images":[],"kind":"PDF","no":4,"pdf":"' + PDF + '",' +
  '"title":"Paper","url":null},' +
  '{"audio":"' + AUDIO + '","body":null,"description":null,"images":[],"kind":"AUDIO","no":5,"pdf":null,' +
  '"title":"Listen","url":null},' +
  '{"audio":null,"body":null,"description":null,"images":[{"caption":"The tower","image":"' + PICTURE + '","no":1}],' +
  '"kind":"IMAGES","no":6,"pdf":null,"title":"Pictures","url":null}],' +
  '"questions":[' +
  '{"image":"' + PICTURE + '","no":1,"options":[{"correct":true,"image":null,"no":1,"text":"Paris"},' +
  '{"correct":false,"image":null,"no":2,"text":"Lyon"}],"points":2,"stem":"Capital of France?"},' +
  '{"image":null,"no":2,"options":[{"correct":false,"image":null,"no":1,"text":"Osaka"},' +
  '{"correct":true,"image":"' + OPTION_IMAGE + '","no":2,"text":null}],"points":3,"stem":"Capital of Japan?"}],' +
  '"schema":1,"timeLimitSec":300,"title":"World capitals"}'
