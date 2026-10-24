# TV-Launcher: knowledge for Sentra

What the `tv-command` skill may answer and which Kyivstar TV channels the launcher knows. Generated from the code:
`tools/tmdb-proxy/ai.js` (`COMMAND_SCHEMA`), `launcher/.../MainActivity.java` (`doAiAction`) and
`launcher/app/src/main/assets/channels/com.kyivstar.tv.tvapp.json`. When those change, this file must change too.

## tv-command: the request

`POST /ai/command` on the `tv-launcher-tmdb` Worker. The TV sends what was said and what is on this TV right now;
the Worker passes it to Sentra as lines `id | name | app`:

```json
{
  "text": "увімкни новини",
  "apps":     [{ "pkg": "com.kyivstar.tv.tvapp", "label": "Київстар ТБ" }],
  "channels": [{ "id": "55efdb73e4b0781039bc046c", "name": "2+2 HD", "app": "com.kyivstar.tv.tvapp" }],
  "titles":   [{ "title": "Динамо — Шахтар", "app": "com.kyivstar.tv.tvapp", "live": true }]
}
```

Limits: `text` up to 300 characters, up to 80 apps, 400 channels and 80 home-screen titles.

## tv-command: the answer

Exactly one JSON object; every key is required, an unused one is `""`:

```json
{ "action": "open_channel", "app": "com.kyivstar.tv.tvapp", "channel_id": "55efdb73e4b0781039bc046c",
  "title": "", "query": "", "say": "Вмикаю 2+2" }
```

| Key | Type | Meaning |
|---|---|---|
| `action` | string, one of the eight below | what the TV does |
| `app` | string | package of the app to use, taken only from the request's `apps` or `channels` |
| `channel_id` | string | for `open_channel`: an `id` from the request's `channels` |
| `title` | string | for `open_title`: a title exactly as listed in the request's `titles` |
| `query` | string | search words for `youtube_search`, `spotify`, `search`; the mood or request for `picks` |
| `say` | string | at most six words for the TV to show, in the user's language |

An answer that is not valid JSON or does not match this shape is dropped by the Worker; the TV then gets
`{"action":"search","query":<the spoken text>}` and searches for what was said.

## tv-command: the eight actions

| `action` | When | Keys used | What the TV does | When the TV cannot do it |
|---|---|---|---|---|
| `open_channel` | a TV channel by name or topic ("новини" → a news channel, "футбол" → a sports channel) | `app`, `channel_id` | opens that live channel in its app (Kyivstar TV: deep link `com.kyivstar.tv.tvapp://live<id>?liveId=<id>`) | the id is not among the app's channels: plain search results stay |
| `open_title` | one of the home-screen titles is clearly what was asked (a live match on now) | `app`, `title` | opens that title from the shelf; for KIK it may look the title up in KIK | title not on the shelf: plain results |
| `youtube_search` | a YouTube video, stream, music video or topic, when no listed title fits | `app`, `query` | opens YouTube search results for `query` in `app`, else SmartTube (`org.smarttube.stable`), else YouTube (`com.google.android.youtube.tv`) | no YouTube app or empty `query` |
| `spotify` | music, a song, an artist, a playlist, a mood for listening | `app`, `query` | `spotify:search:<query>` in `app` or Spotify (`com.spotify.tv.android`); with no `query` just opens Spotify | Spotify not installed |
| `picks` | suggestions of films or series ("щось смішне на вечір") | `query` | asks `/ai/picks` (skill `tv-picks`) and shows the films as tiles | — |
| `open_app` | an app by name | `app` | launches that package | package not installed |
| `search` | none of the above fits, but a name was said | `query` | puts `query` in the search field and opens the best match | empty `query` |
| `none` | nothing on this TV can do it | `say` | shows `say` as a note; the plain search results stay | — |

"наживо" / "live" means: prefer what is live right now (`titles` with `"live": true`).

## Kyivstar TV channels the launcher knows

App `com.kyivstar.tv.tvapp`, 451 channels. `id` is what `channel_id` carries; the channel opens with
`com.kyivstar.tv.tvapp://live<id>?liveId=<id>`. "Popular" is Kyivstar TV's own ranking (1 = most watched), for the
25 channels it ranks. The TV sends only the channels this household actually has in the request, so an id
from this list that is not in the request must not be answered.


### Entertainment (43)

| № | Channel | id | Popular |
|---|---|---|---|
| 1 | 1+1 Україна HD | `63a1cdb84c3452c24a804dfd` | 1 |
| 2 | 2+2 HD | `55efdb73e4b0781039bc046c` | 6 |
| 2 | ТЕТ HD | `55efdb73e4b0781039bc0487` | 5 |
| 3 | Новий канал HD | `55efdb73e4b0781039bc0544` | 4 |
| 4 | ICTV 2 HD | `63a1cebb4c3452c24a84352f` | 3 |
| 5 | СТБ HD | `55efdb73e4b0781039bc055f` | 2 |
| 7 | КС ТБ \| Говорить вся країна | `648adbe1e4b03140695bf056` |  |
| 7 | КС ТБ \| Одруження наосліп | `64f087cc19f40ed82ae0c36a` |  |
| 7 | НТН HD | `55efdb73e4b0781039bc03e5` | 13 |
| 9 | ICTV HD | `55efdb73e4b0781039bc057a` | 12 |
| 12 | SUPER+ HD | `676d687cb7140a1fef5b97e7` |  |
| 13 | ОЦЕ | `55efdb73e4b0781039bc05cb` |  |
| 14 | RAZ 3 | `55efdb75e4b0781039bc09e6` |  |
| 16 | Інтер HD | `5ab9e7e760b20c61c1efec00` | 25 |
| 17 | К1 HD | `55efdb73e4b0781039bc0394` |  |
| 18 | К2 HD | `55efdb73e4b0781039bc03af` |  |
| 26 | ДІМ HD | `637c96307014ebf0127ba621` |  |
| 55 | КС ТБ \| Панянка-селянка | `64b907ede4b071015a34cc2f` |  |
| 60 | КС ТБ \| Міняю жінку | `6492fc238b94b9c419bc3075` |  |
| 61 | КС ТБ \| Топ Гір | `664dcdbfe4b05b94c83338e3` |  |
| 72 | Рада | `55efdb74e4b0781039bc0652` |  |
| 96 | КС ТБ \| Реаліті | `64e6006546744d662a6e6353` |  |
| 99 | КС ТБ \| Трейлер HD | `5f99281055b4a56770ddf089` |  |
| 112 | КС ТБ \| Стосується кожного+ | `6790eeef0594ce86e473b094` |  |
| 146 | TV5Monde | `6584026de4b0851c74698583` |  |
| 168 | КС ТБ \| Реаліті-шоу HD | `60f57fa2d0348f88859b2c60` |  |
| 172 | КС ТБ \| Богиня шопінгу | `64afa382e4b07101487321b1` |  |
| 173 | КС ТБ \| ТРОФЕЙ HD Premium | `65c33d4ce4b04e81a86b90cc` |  |
| 217 | TLC | `55efdb77e4b0781039bc0efb` |  |
| 256 | Континент Е | `63ad8acd63b46c5d26818a1f` |  |
| 257 | Люкс ТБ | `6a4fac7299b4841d54c4d1ab` |  |
| 258 | Конкурент.Україна | `683f11e253d4c0d8b30df19b` |  |
| 259 | Zoom HD | `55efdb73e4b0781039bc0436` |  |
| 260 | Ми Україна+ HD | `660568ac14f4b3f942099f6c` |  |
| 264 | Бікіні | `6a07415f74b4921921570481` |  |
| 266 | Світло | `5906304f5f34ba4a8ffb2aff` |  |
| 267 | PV+ | `6270010f1d54b220a6e82b53` |  |
| 276 | FASHION HD | `630776c043f40f192ec153e7` |  |
| 278 | Fashion TV | `56e2b647e4b0b3eebdd851a2` |  |
| 279 | Fashion TV HD | `57147246e4b006ddd602c415` |  |
| 281 | UA Fashion TV | `62442f03527400192f86c6cf` |  |
| 282 | Закон ТБ | `62442da3527400192f84653d` |  |
| 283 | Масон ТБ | `62442e80527400192f85f970` |  |

### Films and series (113)

| № | Channel | id | Popular |
|---|---|---|---|
| 6 | Бігуді | `55efdb73e4b0781039bc04d8` |  |
| 7 | КС ТБ \| CSI: Місце злочину | `690bab9211d4c745a1d5401d` |  |
| 7 | КС ТБ \| Боєць | `62961a74a4f4e57c0343ca1a` |  |
| 7 | КС ТБ \| Добре кіно | `629618e6a4f4e57c0341f679` |  |
| 7 | КС ТБ \| Елементарно HD | `6528f34dc814a9ac356f3a25` |  |
| 7 | КС ТБ \| Зачаровані | `6508533a5194ee6d755fe9d5` |  |
| 7 | КС ТБ \| Злочин у раю | `66c6f712367436b05767b24a` |  |
| 7 | КС ТБ \| Комедія HD | `5f99574855b4a5677105b49e` |  |
| 10 | УНІАН Серіал | `55efdb73e4b0781039bc04f3` |  |
| 15 | Твій серіал HD | `658ee723e4b07f6423f2e5ee` |  |
| 20 | Суспільне Культура | `55efdb75e4b0781039bc0a3c` |  |
| 35 | ДІМ UA | `68b155ba9d544fc80c9a2429` |  |
| 36 | КС ТБ \| Всесвіт Єллоустоун | `6a62251a9074b5b8bf1fd918` |  |
| 36 | КС ТБ \| Жахи HD | `5fc63d815414554c17e49002` |  |
| 36 | КС ТБ \| Культове кіно 90-х | `6901f7244b74878c14e819e2` |  |
| 36 | КС ТБ \| Трилер | `667bfe371034a3a64e21afbd` |  |
| 39 | КС ТБ \| Культове кіно 00-х | `69158febd9b4e7b5b3aa8e4e` |  |
| 40 | КС ТБ \| Серіали про лікарів | `646df4f5e4b0f13e55a40d2f` |  |
| 41 | КС ТБ \| Криваві квіти | `68bfe8309bb45746b3868a4c` |  |
| 42 | КС ТБ \| Турецькі серіали | `689c401d9934e5e0d281e40f` |  |
| 43 | КС ТБ \| Безкоштовне кіно | `6ac53b4fe4b02436d94ef2d0` |  |
| 44 | КС ТБ \| Сімейні мелодрами | `6492fae38b94b9c419a4aa41` |  |
| 45 | КС ТБ \| Детектив HD | `60f57a58d0348f888597bae9` |  |
| 46 | КС ТБ \| Кримінал HD | `633458df50347fd195aeeb37` |  |
| 47 | КС ТБ \| Action | `64d60021e4b02862812b970c` |  |
| 48 | КС ТБ \| Сила кохання Феріхи | `66bc62b478f4cf180587f171` |  |
| 49 | КС ТБ \| Нескінченне кохання | `655ccb45e4b091f07c72ecd7` |  |
| 50 | КС ТБ \| Романтичні серіали | `632982f766d411c45f59e106` |  |
| 51 | КС ТБ \| Фентезі | `68a4d76874f4e4bce9d1fba6` |  |
| 52 | КС ТБ \| Міс Марпл Агати Крісті | `6656f833e4b0349909e157d5` |  |
| 53 | КС ТБ \| Отець Браун | `664dc7d2e4b05b94c828f3e0` |  |
| 54 | КС ТБ \| Розслідування Мердока | `6656df50e4b03499096fefd5` |  |
| 56 | КС ТБ \| Комедія + HD | `6425bcbc6e1448e07191cb7b` |  |
| 59 | КС ТБ \| Наречена зі Стамбула | `685bb3ee1bb407340cee5b82` |  |
| 62 | КС ТБ \| Кохання напрокат | `68664d4ebd749e48ab827450` |  |
| 63 | КС ТБ \| Життя відомих людей | `6492f7f08b94b9c41993be28` |  |
| 64 | КС ТБ \| Жінка | `6ac53c32e4b02436d94fe8fa` |  |
| 65 | КС ТБ \| Рецепт кохання | `6878a6c4c9f4a1317711560a` |  |
| 66 | КС ТБ \| Заборонене кохання | `69a55a5d405401e5acc4206b` |  |
| 79 | КС ТБ \| Костюмована драма | `68a4d7bd74f4e4bce9d20fa6` |  |
| 81 | КС ТБ \| Сімейне кіно HD | `60a7816172f48fe0cd659ddb` |  |
| 82 | КС ТБ \| Опер за викликом | `64f83902e4b0a1c5db44272d` |  |
| 83 | КС ТБ \| Постукай в мої двері | `677d13e57c34526899e67701` |  |
| 84 | КС ТБ \| Заборонений плід | `6672af58e4b0e65f5c9722e6` |  |
| 85 | КС ТБ \| Зрада | `680a3900a5146efef43cda95` |  |
| 86 | КС ТБ \| Кохання проти долі | `677d01c38b14b5f80056277d` |  |
| 87 | КС ТБ \| Драма | `64e5f87646744d662a28159f` |  |
| 88 | КС ТБ \| Дорама HD | `64b90aeee4b071015a3d08b2` |  |
| 89 | КС ТБ \| Любов не розуміє слів | `6620cdabe4b0e42bf3725d65` |  |
| 90 | КС ТБ \| Кіно та серіали+ | `66a22748fc34a4d5048e9eb6` |  |
| 91 | КС ТБ \| Декстер | `670fd10f10f40b608830247a` |  |
| 92 | КС ТБ \| Скрізь тільки ти | `6881ff733f1447f80c3e3c8e` |  |
| 93 | КС ТБ \| Наші серіали HD | `60353b1ffe34f89e3586b513` |  |
| 94 | КС ТБ \| Речдок | `6787b02d4f34b6e890b08c56` |  |
| 100 | Viasat Kino HD | `55efdb75e4b0781039bc0a8d` |  |
| 101 | КС ТБ \| Бойовик HD | `6597e869e4b01fb1328077f0` |  |
| 102 | Viasat Kino Action HD | `55efdb75e4b0781039bc0ac3` |  |
| 103 | Viasat Kino Comedy HD | `55efdb76e4b0781039bc0b9b` |  |
| 104 | КС ТБ \| Мегахіт | `6952741db1741a95fb0d9ed5` |  |
| 105 | Epic Drama HD | `5f9eacfd68745623f6597f61` |  |
| 106 | Cine + HD | `5fc4da161d544ddbce8b38fa` |  |
| 107 | Cine + LEGEND | `5fc4dbc51d544ddbce8beb58` |  |
| 108 | Cine + HIT HD | `5fc4dcf31d544ddbce8c2d0c` | 22 |
| 109 | Твоє Кіно RELAX HD | `676d8766b7140a1fef9c071c` |  |
| 110 | Твоє Кіно ACTION HD | `676d7d55b7140a1fef89741d` |  |
| 111 | Твоє Кіно ХІТ HD | `676d766ab7140a1fef7555d9` |  |
| 113 | КС ТБ \| Серіал HD | `5fc643105414554c17e4ce62` |  |
| 114 | КС ТБ \| Гумор | `64e5fd4e46744d662a50a976` |  |
| 115 | КС ТБ \| Містика | `64e5f1f446744d662a0b80c2` |  |
| 116 | КС ТБ \| Команда | `64e5b20c6fb4afb023b742eb` |  |
| 117 | Viasat Kino World | `55efdb75e4b0781039bc0aa8` |  |
| 118 | Viasat True Crime | `6965fc3b5f94b00e0b8b6e82` |  |
| 119 | AMC HD | `66c87a5a3674690dd5f56c1f` |  |
| 120 | Hollywood | `611a1df0feb4c2e51bf05dc4` |  |
| 121 | Hollywood HD | `611a1ec4feb4c2e51bf108d9` |  |
| 122 | FILMUADRAMA HD | `5c01323de4b0f16ffce5824a` | 21 |
| 123 | Bolt HD | `5f0452a7f0f4201b22763474` | 20 |
| 124 | Bolt | `5a37a21360b2876d30778a72` |  |
| 125 | PROKINO | `64f1ed5c19f40ed835708373` |  |
| 126 | Star Cinema | `5a37a30460b2876d30778e43` |  |
| 128 | Kinoliving HD | `63d397e85a54b35c0c57a98c` |  |
| 129 | Kinowood HD | `644fd6af6274c9ef1847e830` |  |
| 130 | Kinosweet HD | `657311894a14d75b0695ea22` |  |
| 132 | Viasat Serial HD | `6544d132ddd42503c4c623e3` |  |
| 133 | FX | `5c9fe2f1e4b0b702df2a1d1a` |  |
| 134 | FX Life | `60f5378bd0348f888563bc73` |  |
| 135 | Star Cinema HD | `5f044e5df0f4201b227614e9` |  |
| 137 | Дорама | `5c010770e4b0f16ffce5234d` |  |
| 138 | NOW Series HD | `658ee22ee4b07f6423e6855a` |  |
| 139 | Сонце+ | `69eb4b81f7d4dc51d8f8d2fc` |  |
| 140 | Enter-фільм HD | `55efdb73e4b0781039bc041b` |  |
| 142 | Flix Snip | `5fec60431814a6b006c1dbeb` |  |
| 143 | KINOGRANDE | `657c5f79e4b0b28d7b43e7ea` |  |
| 144 | Кіно 1 HD | `627001e21d54b220a6eeaceb` |  |
| 145 | KINO 2 HD | `66324b18e4b00500b12e1edc` |  |
| 147 | КС ТБ \| Comedyland | `64d5fc8ae4b028628122256f` |  |
| 149 | КС ТБ \| Brilliant | `64d6014ee4b02862812bba62` |  |
| 150 | КС ТБ \| Драматичне кіно HD | `60a7807d72f48fe0cd655927` |  |
| 152 | КС ТБ \| Мелодрама HD | `60f57eaad0348f88859a8eeb` |  |
| 153 | КС ТБ \| Комедійні серіали | `64c0c9f6c394fe566c16768e` |  |
| 154 | КС ТБ \| Перелітний птах | `6915de5cfa9460f30a1e0a26` |  |
| 155 | КС ТБ \| Сім'я | `6915e60187944f811a0570ac` |  |
| 156 | КС ТБ \| Дикий | `6a0ef494f754405d1b8cbc64` |  |
| 158 | КС ТБ \| Солодка помста | `6966d39649f4b714fa019325` |  |
| 159 | КС ТБ \| Сльози Дженнет | `6a57de8415f42de8fa04d168` |  |
| 160 | КС ТБ \| Лейла | `6a7d7c9d31d4be794e5b363f` |  |
| 162 | КС ТБ \| Червоні бутони | `696f550ce4b08983bb3d564e` |  |
| 163 | ДРАМА ТБ | `67b49ffda534dccfca27e8c3` |  |
| 164 | МЕДИЧНІ СЕРІАЛИ | `67bde6c21bd47010c9a41079` |  |
| 165 | МІСТИЧНІ ІСТОРІЇ | `67c6f3519074a29cab173ffc` |  |
| 166 | МЕЛОДРАМИ UA | `67ab78ced694ae41ff45c124` |  |
| 203 | FX English | `66edaf73bb34a213083a82cc` |  |
| 204 | FX Life English | `66edb0aabb34a213083de896` |  |

### News (17)

| № | Channel | id | Popular |
|---|---|---|---|
| 8 | 1+1 МАРАФОН HD | `55f2bcefe4b0781039beb055` | 7 |
| 19 | Перший HD | `55efdb72e4b0781039bc0340` |  |
| 23 | Прямий | `55efdb73e4b0781039bc0601` | 17 |
| 33 | Київ | `55efdb74e4b0781039bc066d` |  |
| 67 | Еспресо | `58a15a5430d48b494bcc67c2` | 16 |
| 68 | 24 канал | `55efdb74e4b0781039bc0760` |  |
| 69 | FREEДОМ | `56533ef8e4b02194ff66bb33` |  |
| 70 | 5 канал | `55fefa0ae4b036638ef0b606` |  |
| 71 | Апостроф ТБ HD | `5f195ae68d54597042569a34` |  |
| 73 | Армія TV | `64b149a2e4b071014c8dd390` |  |
| 74 | Euronews | `5bb082f260b2f2d5070b46bb` |  |
| 77 | DW | `5805e3c27134f20c943473b6` |  |
| 78 | Настоящее время | `5898569230d48b494bc850ce` |  |
| 269 | France 24 En | `5894711530d48b494bc6a824` |  |
| 273 | Euronews English HD | `5f4dc5601774d4de88e5096e` |  |
| 275 | France 24 Fr | `589471bb30d48b494bc6ad8c` |  |
| 341 | France 24 Arabic | `622b59ce8cb40e74c42662d9` |  |

### Sport (45)

| № | Channel | id | Popular |
|---|---|---|---|
| 299 | КС ТБ \| Хокей | `68af3c20cd14ace644fd63f8` |  |
| 300 | УПЛ.ТБ HD | `65d752acb5f4696e681d8631` |  |
| 301 | Setanta Sports Premium HD | `676d3b72b7140a1feeaf0efb` | 9 |
| 302 | Setanta Sports HD | `5f7482451774d4de8a615dd5` | 8 |
| 303 | Setanta Sports + HD | `5f7482c51774d4de8a615eeb` |  |
| 305 | КС ТБ \| Волейбол | `68a3692c99343850f6877efe` |  |
| 305 | КС ТБ \| Медіафутбол | `678a9c3cb41478cfe9032052` |  |
| 305 | КС ТБ \| Спорт | `68a36a8399343850f68fc042` |  |
| 306 | Maincast Sport | `695e4db1c7541a5f0907c1d0` |  |
| 306 | Суспільне Спорт HD | `64da16bf6fb4afb00d17519f` |  |
| 307 | Eurosport HD | `55efdb77e4b0781039bc0ec5` | 23 |
| 308 | Eurosport English | `58c24f0830d48b494be7c3e8` |  |
| 308 | КС ТБ \| Автоспорт | `69f337346234a8697e8b32cf` |  |
| 309 | Eurosport 2 | `55efdb77e4b0781039bc0ee0` |  |
| 310 | КС ТБ \| Бокс HD | `677fbc926dd4db9a0d1ae4bc` |  |
| 311 | Sport 1 | `61e54c8bbcb489f48b98253a` |  |
| 312 | Sport 2 | `61e55036bcb489f48b9a726d` |  |
| 313 | Sport 3 HD | `61e54d66bcb489f48b98b64e` |  |
| 314 | Sport 4 | `61e550abbcb489f48b9b02e8` |  |
| 315 | Sport 1 Baltic | `61e5518dbcb489f48babe915` |  |
| 317 | КС ТБ \| Баскетбол | `6166e12578b43e5a4e065097` |  |
| 318 | Maincast Кіберспорт | `674a2b4e6c14d3b92d832ace` |  |
| 319 | КС ТБ \| Сокка | `691f105893343913ac4539b4` |  |
| 320 | Dynamo Kyiv TV HD | `61e54432bcb489f48b8ec7ad` |  |
| 320 | КС ТБ \| Спортивний | `6ac6601de4b0019444eb26d8` |  |
| 321 | КС ТБ \| Футзал | `67e5739abc943114302c64ff` |  |
| 321 | КС ТБ \| Футзал 2 | `6a8df710881450d1c86feb81` |  |
| 321 | КС ТБ \| Футзал 3 | `6a8df80a881450d1c8707200` |  |
| 324 | Трофей | `5b41dfe460b2315d91c9088e` |  |
| 325 | Рибалка HD | `5efcfda37a949d862f6ee3ee` |  |
| 326 | Перший автомобільний | `5c29427ee4b06b9e5999907a` |  |
| 327 | Divisport HD | `6569e6984a14d75bf8501acc` |  |
| 328 | Cars&Stars TV HD | `6064ecd02834f0c8b6867301` |  |
| 329 | УПЛ.ТБ Sub 1 HD | `65d75483b5f4696e682b98fc` |  |
| 330 | УПЛ.ТБ Sub 2 HD | `65d75546b5f4696e683d828e` |  |
| 331 | УПЛ.ТБ Sub 3 HD | `65d75609b5f4696e68425753` |  |
| 332 | Спорт 5 | `67cadf9ff174deaf8810b0b6` |  |
| 335 | Equalympic | `6125f47ff51485075ca3aae8` |  |
| 336 | Trace Sport Stars HD | `5fec63b11814a6b006c2cb1c` |  |
| 337 | Eurosport  2 English | `66acf7472254a13f599cc629` |  |
| 338 | КС ТБ \| Футбол | `68fb631b4eb468b99819f75b` |  |
| 338 | КС ТБ \| Футбол 2 | `68fb653f4eb468b99832891a` |  |
| 338 | КС ТБ \| Футбол 3 | `6a5a2aea87b491a656de4d98` |  |
| 339 | КС ТБ \| Баскетбол 2 | `68a87facdf14eb24b2bce200` |  |
| 339 | КС ТБ \| Баскетбол 3 | `6a466b17929408ce7df4163d` |  |

### Kids (24)

| № | Channel | id | Popular |
|---|---|---|---|
| 443 | КС ТБ \| Бульки-гупі | `653a1babe4b096bc30713e83` |  |
| 444 | КС ТБ \| Черепашки-ніндзя | `64f877b4e4b0a1c5df0f1a00` |  |
| 445 | КС ТБ \| Kids+ | `64d60202e4b02862812bdf7a` |  |
| 446 | КС ТБ \| Kids Planet | `64d602eee4b0286281387875` |  |
| 447 | КС ТБ \| LEGO | `62fa02a618f45f37f1c11b8d` |  |
| 448 | КС ТБ \| Аудіоказки | `61c48647b054c5945df39c44` |  |
| 449 | КС ТБ \| Пінгвіни Мадагаскару | `6a6b5d2fc3144f2ec4dae82f` |  |
| 450 | КС ТБ \| Оггі та кукарачі | `6645ce32e4b0f26f093362e2` |  |
| 451 | КС ТБ \| Мультоленд | `5fc642585414554c17e4cb9e` |  |
| 452 | КС ТБ \| Щенячий патруль HD | `60f582e8d0348f88859cec49` |  |
| 456 | DUCK TV | `5c013156e4b0f16ffce57335` |  |
| 458 | Da Vinci | `5efcf8147a949d862f6e859b` |  |
| 459 | Піксель HD | `55efdb73e4b0781039bc0400` | 19 |
| 460 | ПЛЮСПЛЮС HD | `55efdb73e4b0781039bc04a2` | 18 |
| 462 | Lale | `55efdb75e4b0781039bc090e` |  |
| 463 | DUCK TV HD | `5f044d77f0f4201b2275df74` |  |
| 464 | DUCK TV PLUS | `5f4dc4251774d4de88e4aca5` |  |
| 466 | Niki Kids HD | `5fc4d8c61d544ddbce8b007a` |  |
| 467 | Niki Junior HD | `5fc4da5c1d544ddbce8b3b94` |  |
| 468 | Cine + KIDS HD | `5fc4df611d544ddbce8ddfbd` |  |
| 469 | KinoKazka | `63ad8c2163b46c5d26896057` |  |
| 471 | КС ТБ \| Свинка Пеппа | `6582d716e4b05f90a92aa106` |  |
| 473 | КС ТБ \| Губка Боб HD | `60f580e1d0348f88859bda69` |  |
| 474 | КС ТБ \| KIDDISVIT | `67ff5b7cec14b22e85eb5659` |  |

### Knowledge and documentary (49)

| № | Channel | id | Popular |
|---|---|---|---|
| 7 | КС ТБ \| Світ навиворіт HD | `5fd36b545414fee0a809cc16` |  |
| 11 | Світ+ | `6724df025ff410530cf71e1f` |  |
| 57 | КС ТБ \| Світ навиворіт. Південна Америка HD | `65085645e4b00819f395d48e` |  |
| 58 | КС ТБ \| Світ навиворіт. Азія HD | `65085677e4b00819f3968ca3` |  |
| 118 | КС ТБ \| Explore | `6966d20649f4b714fa018b39` |  |
| 118 | КС ТБ \| History | `6966d17549f4b714fa0187b0` |  |
| 118 | КС ТБ \| Nature | `6966d28249f4b714fa018f4b` |  |
| 157 | КС ТБ \| Реальна історія | `692ec4fe4eb474f8ce9f2c8a` |  |
| 169 | КС ТБ \| Історія | `64e5bc90e4b01e556c0af0cd` |  |
| 200 | Viasat History HD | `5646025ee4b030cb88f2ca9e` | 14 |
| 201 | Viasat Explore | `55efdb75e4b0781039bc0b14` |  |
| 202 | Viasat Nature | `55efdb75e4b0781039bc0b2f` |  |
| 205 | National Geographic | `5c9fe402e4b0b702df2a2e37` | 11 |
| 206 | National Geographic Wild | `60f53885d0348f8885641085` |  |
| 207 | History HD | `5b7c1bf560b292cf6a9cfd3d` |  |
| 208 | History 2 HD | `62faaade917481358b89561a` |  |
| 209 | Discovery ID HD | `5fe9e0a11814ed0677cc06a2` |  |
| 210 | Discovery Channel | `55efdb77e4b0781039bc0e59` | 10 |
| 214 | Зоосвіт | `695392a9b1741a95fb887f43` |  |
| 215 | Animal Planet | `5b4c567960b25704b6b0639c` | 15 |
| 216 | Food Network | `58cfc89a30d48b494bfcaa12` |  |
| 218 | КС ТБ \| Загублений Світ | `67ee4ab71774a75ad67b843c` |  |
| 220 | КС ТБ \| Дика природа | `69f31ea96234a8697e801b1c` |  |
| 223 | History HD | `6425e18f6e1448e072668d1e` |  |
| 224 | ECO TV | `55efdb74e4b0781039bc06be` |  |
| 227 | 6 соток HD | `62442a45527400192f73cb8d` |  |
| 229 | Travel XP HD | `6336b3f951d4acd47f6cf8ae` |  |
| 230 | Travel&Food HD | `6270052b1d54b220a6f95f7c` |  |
| 231 | КС ТБ \| Орел і решка | `677fbf316dd4db9a0d2269bf` |  |
| 234 | UTravel | `613af75f96f467e44044829e` |  |
| 235 | Travel Channel | `57ed13b50714ed876c0ff685` |  |
| 236 | Фауна | `590632605f34ba4a8ffb2cf8` |  |
| 237 | Терра | `590631675f34ba4a8ffb2bfc` |  |
| 238 | Наука | `590633095f34ba4a8ffb2da6` |  |
| 240 | КС ТБ \| Документальний HD | `5fc63f265414554c17e4938f` |  |
| 241 | Мега HD | `55efdb73e4b0781039bc03ca` | 24 |
| 243 | One Planet | `63ad87d863b46c5d26802991` |  |
| 244 | English Club HD | `55efdb76e4b0781039bc0c8e` |  |
| 245 | 36,6 | `5c22624fe4b0f16ffd086240` |  |
| 246 | 36,6 HD | `5c226495e4b0f16ffd088340` |  |
| 247 | КУС-КУС | `5e4bc0ebb874c71b030b07ec` |  |
| 250 | RAZ 1 HD | `65bd13dce4b017165a003f68` |  |
| 251 | RAZ 2 HD | `65bd154de4b017165a03418d` |  |
| 252 | LingoToons | `6866536cbd749e48ab969b9e` |  |
| 253 | Tiny Teen | `68665434bd749e48ab96c561` |  |
| 254 | Lang Lab | `686644fe92346d182fc42911` |  |
| 255 | CLASSICAL HARMONY | `686635e726f49755054dcfee` |  |
| 272 | Дача | `561bb18fe4b0116df7025c94` |  |
| 284 | Фронт HD | `658eeb5fe4b07f6423f623c5` |  |

### Music (14)

| № | Channel | id | Popular |
|---|---|---|---|
| 400 | М1 | `55efdb73e4b0781039bc0595` |  |
| 401 | М2 | `55efdb73e4b0781039bc05b0` |  |
| 408 | EU Music HD | `55fefd09e4b036638ef0c9bb` |  |
| 410 | 4ever Music HD | `5fc4db001d544ddbce8bb293` |  |
| 411 | InRating HD | `60e7fe88f9d4e877bf4cf23b` |  |
| 412 | НАШЕ Ретро HD | `630777ee43f40f192ec35032` |  |
| 413 | НАШЕ Music HD | `6307791f43f40f192ec543a6` |  |
| 414 | ЕТНО канал HD | `63077a6843f40f192ec7a0a4` |  |
| 415 | UA Music | `63ad904063b46c5d268c63d0` |  |
| 416 | Black | `63ad8eee63b46c5d268ae261` |  |
| 417 | Trace Urban HD | `5fec62a51814a6b006c27a75` |  |
| 420 | Viva Latino | `6400902fa1943d578e6a4545` |  |
| 423 | Music Box HD | `6544ced0ddd42503c4c3521e` |  |
| 442 | Музвар | `64e642299b1489d46f9bfdef` |  |

### Regional (42)

| № | Channel | id | Popular |
|---|---|---|---|
| 34 | Суспільне Київ | `58086bc00714ed876c1fcd56` |  |
| 347 | NTA | `60b62b0372f48fe0d040280c` |  |
| 348 | Суспільне Крим | `55efdb72e4b0781039bc035b` |  |
| 349 | Суспільне Вінниця | `5f0c312560742308d9356a24` |  |
| 350 | Суспільне Луцьк | `5f0c31c960742308d9356bc3` |  |
| 351 | Суспільне Дніпро | `5f0c327460742308d9356d6c` |  |
| 352 | Суспільне Житомир | `5f0c33ad60742308d9356f29` |  |
| 353 | Суспільне Кропивницький | `5f0c365460742308d935c158` |  |
| 354 | Суспільне Запоріжжя | `5f0c475560742308d936ddc1` |  |
| 355 | Суспільне Івано-Франківськ | `5f0c487060742308d936dfa6` |  |
| 356 | Суспільне Миколаїв | `5f0c49f160742308d937092d` |  |
| 357 | Суспільне Львів | `5f0c4d9b60742308d93785f7` |  |
| 358 | Суспільне Полтава | `5f0c511660742308d9378c78` |  |
| 359 | Суспільне Одеса | `5f0c52ab60742308d9378ec6` |  |
| 360 | Суспільне Тернопіль | `5f194e508d5459704254aa0d` |  |
| 361 | Суспільне Рівне | `5f194f378d5459704254d2ef` |  |
| 362 | Суспільне Суми | `5f194faa8d54597042552446` |  |
| 363 | Суспільне Хмельницький | `5f19500c8d54597042552bdd` |  |
| 364 | Суспільне Донбас | `638f2eb87bd4ac19ad17fc6a` |  |
| 365 | Суспільне Харків | `5f1951208d545970425574c1` |  |
| 366 | Суспільне Херсон | `5f19519e8d545970425582c5` |  |
| 367 | Суспільне Чернігів | `5f1951f98d545970425583f9` |  |
| 368 | Суспільне Черкаси | `5f1952758d54597042558fbd` |  |
| 369 | Суспільне Чернівці | `5f1952e88d5459704255a9c8` |  |
| 370 | Суспільне Ужгород | `55efdb75e4b0781039bc08d8` |  |
| 371 | ATR | `55efdb75e4b0781039bc08f3` |  |
| 372 | Капучино TV | `55efdb75e4b0781039bc09cb` |  |
| 373 | Перший Західний | `55efdb75e4b0781039bc09b0` |  |
| 374 | Аверс | `5c7babfae4b00d3812e04a31` |  |
| 375 | Д1 | `69d4c5e84bb456719f49595d` |  |
| 376 | ВІТАТБ HD | `631705f672b4c44feb245fb5` |  |
| 377 | ITV MEDIA GROUP HD | `66056baa14f4b3f94222aa7f` |  |
| 378 | Рудана | `658eee07e4b07f6423f928cb` |  |
| 380 | Галичина ТБ | `5f0c299b8d54597041dd7f33` |  |
| 382 | 11 канал | `621d1337fdf4079b25b505b2` |  |
| 383 | СК 1 | `630773bf43f40f192ebf2d6d` |  |
| 384 | Перший міський.KR | `5f1954978d54597042561eba` |  |
| 385 | Правда тут Львів HD | `5f73467b1774d4de8a57a92b` |  |
| 391 | КОНКУРЕНТ ВОЛИНЬ | `61f7a0fce65454b30347b965` |  |
| 393 | Дніпро TV | `621e301fa1b4d2fa38ce0337` |  |
| 600 | ETS | `646604d8201498776aab95fa` |  |
| 601 | ETS_2 | `689b5bb89934e5e0d1ebf9ec` |  |

### Other (34)

| № | Channel | id | Popular |
|---|---|---|---|
| 21 | 8 канал | `55efdb74e4b0781039bc07af` |  |
| 76 | OBOZREVATEL TV | `5a72c4f260b231ffb6c4c9e7` |  |
| 174 | КС ТБ \| Тварини. Релакс 4К | `65705f98c594d7d46e86091d` |  |
| 175 | КС ТБ \| Повітря. Релакс 4К | `65706090c594d7d46e860c45` |  |
| 176 | КС ТБ \| Пляж. Релакс 4К | `65706148c594d7d46e860fa1` |  |
| 177 | КС ТБ \| Понад хмарами. Релакс 4К | `65706208c594d7d46e861872` |  |
| 178 | КС ТБ \| Акваріум. Релакс 4К | `6570628bc594d7d46e861b29` |  |
| 179 | КС ТБ \| Земля. Релакс 4К | `657062ffc594d7d46e861dcb` |  |
| 180 | КС ТБ \| Колискова. Релакс 4К | `657063e8c594d7d46e86263a` |  |
| 181 | КС ТБ \| Вогонь. Релакс 4К | `657064adc594d7d46e8629bb` |  |
| 182 | КС ТБ \| Водоспад. Релакс 4К | `657068a9c594d7d46e865ae8` |  |
| 183 | КС ТБ \| Вода. Релакс 4К | `6570694ac594d7d46e865e48` |  |
| 184 | УЗ ТБ \| Потяг Додому | `65ba0c8c8e74610f66bcf541` |  |
| 185 | КС ТБ \| Космос. Релакс | `644a18bff8f476b40042739f` |  |
| 188 | КС ТБ \| Кремль палає. 4К | `65d5fb4fe4b0d45a437e623d` |  |
| 189 | КС ТБ \| Кремль палає | `6304e56c32f43b289c680992` |  |
| 190 | КС ТБ \| Вода. Релакс | `611bc98ffeb4c2e51c22054f` |  |
| 191 | КС ТБ \| Акваріум. Релакс | `611d07c9f51485075ae46a9b` |  |
| 192 | КС ТБ \| Земля. Релакс | `611d089af51485075ae46c6a` |  |
| 195 | КС ТБ \| Вогонь. Релакс | `611d0bcaf51485075ae7bbda` |  |
| 197 | КС ТБ \| Тварини. Релакс | `611d0d69f51485075ae8dc9c` |  |
| 198 | КС ТБ \| Колискова. Релакс | `611d0e29f51485075ae8e50a` |  |
| 248 | Чемодан ТВ HD | `627003031d54b220a6f25279` |  |
| 249 | Соль ТВ HD | `627003d81d54b220a6f880d2` |  |
| 261 | Milady | `55efdb75e4b0781039bc0a57` |  |
| 262 | КС ТБ \| Будь Helsi | `65eb06aee4b05a430356a10c` |  |
| 263 | Караван TV | `5a16f4a960b2b10251c39c43` |  |
| 265 | Телевсесвіт | `5b51f77760b2315d91d9f717` |  |
| 271 | Надія | `5f4dc5f31774d4de88e537ad` |  |
| 274 | Наталі | `55efdb75e4b0781039bc0929` |  |
| 340 | Bloomberg TV | `5b43b09860b25704b69e6c4d` |  |
| 508 | Lounge FM | `60646fce2834f0c8b67b83c6` |  |
| 518 | NRJ | `6064bb7b2834f0c8b67e6ae8` |  |
| 537 | Ми з України | `62d19492221416648aa9a29a` |  |

### Radio (61)

| № | Channel | id | Popular |
|---|---|---|---|
| 488 | КС ТБ \| Golden Hits | `633e90853a7466f6e07f4987` |  |
| 489 | КС ТБ \| World Charts | `633e915e3a7466f6e081b759` |  |
| 490 | КС ТБ \| Made in Ukraine | `633e923b3a7466f6e083f07c` |  |
| 491 | КС ТБ \| HipHop&RnB | `633e92e23a7466f6e0850d83` |  |
| 492 | КС ТБ \| Retro | `633e93703a7466f6e0874874` |  |
| 493 | КС ТБ \| Electro | `633e94433a7466f6e08a418d` |  |
| 494 | КС ТБ \| Sport | `633e949c3a7466f6e08d83d3` |  |
| 495 | Єдині новини (радіо) | `62236f147534b8d219cb39f4` |  |
| 496 | КС ТБ \| TOP HITS | `60b66279c3348470d4ec7657` |  |
| 497 | КС ТБ \| CHILL | `60b663c6c3348470d4ecbb33` |  |
| 499 | КС ТБ \| ROCK | `60b66665c3348470d4f1191f` |  |
| 500 | Хіт FM | `5fed80121814a6b006ee9f7e` |  |
| 501 | Radio ROKS | `5fed80ca1814a6b006eed8d1` |  |
| 502 | Classic Radio | `625557cf9834171c86b98e07` |  |
| 503 | Мелодія FM | `5fed82de1814a6b006ef4d9e` |  |
| 504 | Радіо Relax | `5fed83b81814a6b006ef87e1` |  |
| 505 | Kiss FM | `5fed84441814a6b006f1117e` |  |
| 506 | Наше Радіо | `5fed84b61814a6b006f16949` |  |
| 507 | Radio Jazz | `5fed853e1814a6b006f2b324` |  |
| 509 | Люкс ФМ Chill and Relax | `6a19755cdb94f50c99f527d2` |  |
| 510 | Люкс ФМ Сучасні Хіти | `6a1979b6db94f50c99fbc695` |  |
| 511 | Люкс ФМ Українські Хіти | `6a197afedb94f50c9a0c3e61` |  |
| 512 | Happy Radio TV | `661fde06e4b0253f05d54fc5` |  |
| 513 | Київ FM | `64ccef9ee4b0286271f2ae6f` |  |
| 514 | Авторадіо | `6064a6752834f0c8b67d620d` |  |
| 515 | Люкс ФМ Золоті Хіти | `6a197d10db94f50c9a0d4ad1` |  |
| 516 | K-Pop 24 | `6a197e8edb94f50c9a0ede26` |  |
| 517 | Перець | `61e49bb6bcb489f48b54e4d2` |  |
| 519 | Radio RESPECT | `66c8817c3674690dd5f8ac42` |  |
| 520 | SUN FM | `66fe8015abf4b43606fa2837` |  |
| 521 | ЧЕМПІОН РАДІО | `674b8dbf6c14d3b92eb069cb` |  |
| 522 | Радіо П`ятниця | `6064bdd52834f0c8b67f6042` |  |
| 523 | Радіо Накипіло | `6a073b7474b492192154e35f` |  |
| 524 | Люкс ФМ | `60acbbbe72f48fe0cee6a008` |  |
| 525 | Радіо Maximum | `60acbcac72f48fe0cee7a2cf` |  |
| 526 | Радіо Nostalgie | `60acbdde72f48fe0ceea21aa` |  |
| 527 | Радіо Байрактар | `623f3910f3b44c635db07a4d` |  |
| 528 | Українське радіо | `625848a83bf4d6877b7ed630` |  |
| 529 | Радіо Культура | `62584abf3bf4d6877b8144c1` |  |
| 530 | Радіо Промінь | `625849da3bf4d6877b8075fd` |  |
| 531 | DJFM | `6253d207f0349ca7421b6154` |  |
| 532 | Magic radio | `6253d264f0349ca7421c2e2a` |  |
| 533 | Радіо Шлягер | `6253d55cf0349ca7421cff65` |  |
| 534 | Power FM | `6253d5fcf0349ca7421dcb3c` |  |
| 535 | Радіо Піхота | `692dc89e4eb474f8cd9fd618` |  |
| 536 | Громадське Радіо | `62d192e4221416648aa8b35f` |  |
| 538 | Радіо НВ | `62f2bce510f4ec96fc1c9b30` |  |
| 539 | Радіо Марія | `62d7ccb38d940e85e0d40c3e` |  |
| 540 | Голос Донбасу | `63526aeb847492548d8292cb` |  |
| 541 | Тиса FM | `63526f23847492548d83ac5c` |  |
| 542 | Українське радіо ВСРУ–RUI | `635272d7847492548d8ad9fe` |  |
| 543 | Radio Прищепкін TOP40 UA | `69cce81c3df4c4b573d25beb` |  |
| 544 | Місто над Бугом | `6436a51dbbf43604fb1ca938` |  |
| 545 | Армія FM | `644a67eaf8f476b400ad073a` |  |
| 546 | РАДІО BUG | `676d8d80b7140a1fefa3a221` |  |
| 547 | РАДІОТОЧКА | `67a62afe96d4e4c117ebebc2` |  |
| 548 | Радіо Хартія | `69eb5444f7d4dc51d8ff6848` |  |
| 549 | Люкс ФМ Львів | `6a197fb1db94f50c9a0faad8` |  |
| 550 | Rock 24 | `6a59f387bb94347bc1cd7c78` |  |
| 551 | Love FM | `6a59f4d7bb94347bc1ce0d17` |  |
| 552 | Chillout FM | `6a59f5f5bb94347bc1ce87f4` |  |

### Adult (18+) (9)

| № | Channel | id | Popular |
|---|---|---|---|
| 290 | Playboy | `5afaf2d860b233f72599dcbc` |  |
| 291 | Barely legal TV | `64a55b47e4b0710133fee9e0` |  |
| 292 | Erox | `5afc1b7560b20c61c236a147` |  |
| 293 | Blue Hustler | `6336b70851d4acd47f6e355d` |  |
| 294 | КС ТБ \| Еротика | `63d12a714c14663bb51f19b6` |  |
| 295 | КС ТБ \| Secret 18+ | `63dbc6a13bb40b11d44fc110` |  |
| 296 | КС ТБ \| Hot Films | `64d63314e4b028628206d89b` |  |
| 297 | КС ТБ \| Fantasy art | `64df46116fb4afb01bb8402b` |  |
| 298 | КС ТБ \| Lesbian Love | `64df46a86fb4afb01bb8433c` |  |
