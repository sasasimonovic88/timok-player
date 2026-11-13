# IPTV Player za Android TV (TiviMate-like)

Aplikacija je brendirana za TIM-OK Digital. Primarna platforma je Android TV / Google TV / Fire TV (upravljanje daljinskim, D-pad). Telefon/tablet raspored nije cilj.

## Pravila rada (važno)

- Radi **iterativno po fazama** (vidi "Faze"). Ne pravi sve odjednom. Na kraju svake faze: build mora da prolazi, ukratko mi reci šta je urađeno i šta da testiram na uređaju.
- Posle svake veće izmene pokreni `./gradlew assembleDebug` i ispravi greške pre nego što nastaviš.
- Pre pisanja koda za fazu, predloži kratak plan (fajlovi/paketi) i sačekaj potvrdu samo ako postoji stvarna dilema. Inače nastavi.
- Ne dodaj biblioteke koje nisu navedene bez kratkog obrazloženja.
- Nikada ne hardkoduj kredencijale ili stvarne adrese servera u kodu koji se komituje. Koristi `local.properties` / `BuildConfig` za sideload varijantu (vidi "Build varijante").
- Bez analitike, bez crash-reportera trećih strana, bez reklama.
- Komentari u kodu na engleskom, korisnički tekstovi u `strings.xml` (srpski + engleski).

## Tehnološki stack

- Kotlin, Jetpack Compose + `androidx.tv:tv-material` / `tv-foundation` (fokus, D-pad)
- Media3 (ExoPlayer): HLS, MPEG-TS (progressive/TS), RTSP
- Mrežni sloj: OkHttp + Retrofit + kotlinx.serialization (ili Moshi)
- Baza: Room; podešavanja: DataStore
- DI: Hilt (ili Koin ako je jednostavnije)
- Slike/logoi: Coil sa disk kešom
- Pozadinski poslovi: WorkManager
- XMLTV parsiranje: streaming parser (`XmlPullParser`), nikako DOM, fajl može biti stotine MB
- minSdk 23 (Android 6, stari TV boxovi), targetSdk najnoviji stabilan
- Arhitektura: MVVM, jedan modul `app` za početak, paketi po funkciji (`data`, `domain`, `ui/...`, `player`)

## Izvor podataka: Xtream Codes (primarno)

Korisnik unosi: server (host:port), username, password. Sve ostalo (kanali, EPG, VOD) dolazi sa istog servera.

- Autentikacija / info o nalogu: `GET {server}/player_api.php?username=U&password=P`
  (`user_info`: status, exp_date, max_connections; `server_info`)
- Kategorije uživo: `...&action=get_live_categories`
- Kanali uživo: `...&action=get_live_streams` (opciono `&category_id=`)
- Stream URL uživo: `{server}/live/{username}/{password}/{stream_id}.m3u8` (fallback `.ts`). Format (m3u8/ts) treba da bude podešavanje.
- EPG (glavni način): `GET {server}/xmltv.php?username=U&password=P` (veliki XMLTV fajl, parsirati streaming-om u pozadini, keširati u Room)
- EPG (rezerva, po kanalu): `...&action=get_short_epg&stream_id=ID&limit=N`
- VOD/serije (kasnije): `get_vod_categories`, `get_vod_streams`, `get_series`, `get_series_info`, `get_vod_info`
- Catch-up (kasnije, ako kanal ima `tv_archive=1`): `{server}/timeshift/{username}/{password}/{duration_min}/{YYYY-MM-DD:HH-MM}/{stream_id}.ts`

Napomene:
- EPG vremena su često u UTC ili u serverskoj zoni. Predvidi podešavanje pomeraja (sati) po playlisti.
- Poštuj `max_connections`. Pri zameni kanala zatvori prethodni stream pre otvaranja novog.
- M3U/M3U8 URL je **opciona rezervna** opcija (`group-title`, `tvg-logo`, `tvg-id`, `tvg-name`). Implementiraj iza istog interfejsa `PlaylistSource` kao i Xtream, da se lako dodaje.

## Funkcije po fazama

Oznake: **M** = MVP, **F2** = druga faza, **O** = opciono/kasnije.

### 1. Playlist i izvori
- Dodavanje Xtream naloga: server, korisničko ime, lozinka, provera kroz `player_api.php` (M)
- Dodavanje preko M3U/M3U8 URL-a, kao rezerva (O)
- Više playlisti/profila uz brzo prebacivanje (F2)
- Automatsko osvežavanje liste (interval u podešavanjima) i ručno osvežavanje (F2)
- Opciono polje User-Agent po playlisti. Podrazumevano fiksan, prepoznatljiv User-Agent aplikacije (npr. `TIMOK-Player/<verzija>`) koji se šalje i za API pozive i za ExoPlayer (`DefaultHttpDataSource.Factory().setUserAgent(...)`), kako bi se na serveru po potrebi moglo ograničiti na ovu aplikaciju. Custom headeri nisu potrebni (F2)

### 2. Kanali
- Lista kanala sa logoom i brojem kanala (M)
- Grupe/kategorije sa servera (M)
- Favoriti i sopstvene grupe, uz **ručno sortiranje favorita**: long-press na kanal uključuje režim "Premesti", D-pad gore/dole pomera, OK potvrđuje, Back otkazuje. Redosled se čuva u bazi (polje `position`) (M)
- Pretraga kanala (F2)
- Sakrivanje kanala/grupa, promena redosleda grupa (F2)
- Zaključavanje kanala/grupa PIN-om, roditeljska kontrola (O)
- "Poslednje gledano" (F2)

### 3. Reprodukcija
- HLS, MPEG-TS i RTSP preko Media3/ExoPlayer (M)
- Prelazak kanala gore/dole i direktan unos broja kanala (M)
- Automatsko ponovno povezivanje pri prekidu streama (M)
- Info traka pri promeni kanala: naziv, logo, **trenutna i sledeća emisija** sa progresom (M)
- Povratak na prethodni kanal (F2)
- Izbor audio traga i titlova (F2)
- Izbor dekodera (hardverski/softverski) i podešavanje bafera (F2)
- Format slike: 16:9, 4:3, zoom, fit (F2)

### 4. EPG (TV vodič)
- Učitavanje XMLTV sa istog Xtream servera, bez posebnog polja za EPG URL; parsiranje u pozadini i keširanje u Room (M)
- Trenutna i sledeća emisija u listi kanala i na info traci (M)
- Vodič u obliku mreže (kanali × vreme) (F2)
- Detalji emisije (opis, trajanje) (F2)
- Podešavanje vremenske zone/pomeranja EPG-a (F2)
- Podsetnici za emisije (O)

### 5. Catch-up, timeshift
- Catch-up/arhiva za kanale sa `tv_archive` (O)
- Pauza/premotavanje uživo (timeshift) (O)

### 6. VOD (filmovi i serije)
- Zasebni ekrani za filmove i serije (Xtream) (O)
- Nastavak gledanja od mesta prekida (O)

### 7. Korisnički interfejs (TV)
- Potpuna D-pad navigacija i ispravan fokus na svim ekranima (M)
- Pregled liste kanala preko playera (OK/strelice) (M)
- Brze prečice na daljinskom: CH+/CH-, brojevi, boje (F2)
- Teme/izgled i veličina fonta (O)

### 8. Podešavanja
- Upravljanje playlistama (M)
- Podešavanja plejera: dekoder, bafer, format slike, m3u8/ts (F2)
- Jezik interfejsa: srpski/engleski (F2)
- Automatsko pokretanje pri startu uređaja i direktan ulazak u poslednji kanal (F2)
- Backup/restore podešavanja i favorita (O)

### 9. Tehničko
- Room baza i keširanje logoa (M)
- Obrada grešaka i poruka: neispravni podaci za prijavu, prazna lista, istekao nalog, nema mreže (M)
- Bez analitike, jasna politika privatnosti (M)
- Rad u pozadini (WorkManager) za osvežavanje liste/EPG-a (F2)

### 10. Brendiranje i distribucija (TIM-OK Digital)
- Brendiranje: naziv, ikona, boje, splash, TV banner (M)
- Daljinsko ažuriranje: provera verzije sa mog servera (JSON sa `versionCode`, `apkUrl`, `changelog`) za sideload distribuciju (F2)
- Ekran sa podacima za podršku / kodom za aktivaciju (O)

## Build varijante (product flavors)

- `sideload`: server može biti unapred upisan preko `local.properties` (korisnik unosi samo username/password), uključena provera ažuriranja sa mog servera.
- `play`: bez unapred ubačenog servera ili sadržaja, korisnik sam unosi svoj nalog. Bez self-update mehanizma (Play politika).

## TV UX pravila

- Svaki interaktivni element mora imati vidljiv fokus (jasan okvir/skaliranje).
- Back dugme uvek vraća na logičan prethodni nivo; iz playera zatvara overlay pa tek onda izlazi.
- Overlay (lista kanala, info traka) se automatski zatvara posle nekoliko sekundi neaktivnosti.
- Ne blokiraj UI nit pri učitavanju hiljada kanala: koristi paginaciju/`LazyColumn` sa stabilnim ključevima i Room `Flow`.
- Testirati na realnom Android TV uređaju, ne samo na emulatoru.

## Faze

1. **Faza 1 (MVP):** projekat i tema/brendiranje, Xtream prijava, kategorije i kanali u Room, lista kanala sa grupama, player (HLS/TS) sa zamenom kanala i brojevima, auto-reconnect, favoriti sa ručnim sortiranjem, XMLTV EPG, info traka sa trenutnom/sledećom emisijom, obrada grešaka.
2. **Faza 2:** pretraga, poslednje gledano, prethodni kanal, vodič (mreža), audio/titl izbor, podešavanja plejera, više profila, auto-refresh, jezici, auto-start, update provera.
3. **Faza 3 (opciono):** VOD/serije, catch-up, timeshift, PIN, backup/restore, M3U.

Faza 1 je završena, a veći deo Faze 2 i deo Faze 3 takođe. Trenutno stanje i sledeći koraci su ispod.

## Trenutno stanje (ažurirano 2026-11-14)

**Poslednja verzija: 0.5.0 (versionCode 14)**, `sideloadRelease`, potpisana release ključem (`CN=TIM-OK Digital`, podaci za potpis u `local.properties`). APK: `app/build/outputs/apk/sideload/release/TIMOK-Player-0.5.0.apk`. Za testiranje na sopstvenom boxu koristi se `assembleSideloadStaging` (release brzina, debug ključ, ne distribuira se). Release i staging imaju različit potpis, pa se ne mogu instalirati jedan preko drugog bez brisanja podataka. Kod je na GitHub-u: javni repozitorijum `sasasimonovic88/timok-player` (grana `main`); `local.properties` i `signing/` se nikad ne komituju. Sistemska Java na Macu je 8, pa za `./gradlew` treba `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

### Objava nove verzije (sideload, samo-ažuriranje preko GitHub Releases)

1. Podigni `versionCode`/`versionName` u `app/build.gradle.kts`, napiši `release-notes/<verzija>.md` (srpski, kratke stavke).
2. `./gradlew assembleSideloadRelease`, kopiraj `app-sideload-release.apk` u `TIMOK-Player-<verzija>.apk`.
3. `scripts/make-update-json.sh release-notes/<verzija>.md` → `update.json` (versionCode, apkUrl, sha256, changelog).
4. Komituj, pa iz korisnikovog terminala (sandbox nema pristup GitHub-u): `git push` i `gh release create v<verzija> <apk> <update.json> --title ... --notes-file release-notes/<verzija>.md`.
Aplikacija čita `timok.updateUrl` = `.../releases/latest/download/update.json` (stalni link na poslednji release), proverava jednom dnevno pri pokretanju i na zahtev (O aplikaciji → Proveri ažuriranja), preuzima APK, proverava SHA-256 i otvara sistemski instaler. Release i staging imaju različit ključ: ažuriranje radi samo na release instalacijama.

### Urađeno

- **Nalog i lista:** Xtream prijava sa ponovnim pokušajem (3×), izmena adrese servera bez gubitka favorita (Podešavanja → Nalog), potvrda pre „Ukloni nalog“, automatsko osvežavanje (WorkManager na 12 h + pri vraćanju u aplikaciju; kanali posle 24 h, EPG posle 11 h).
- **Kanali:** grupe, favoriti sa ručnim sortiranjem, sakrivanje kategorija (uživo, filmovi, serije), unos broja kanala u plejeru i iz menija (lista kanala, filmovi, serije, detalji, arhiva — `MainViewModel` + `Routes.NUMBER_ENTRY` u `AppNavHost`; ne na ekranima sa tekstualnim poljima; ista pravila kao u plejeru: prvo pozicija u omiljenim, pa broj kanala), lista prelazi u fullscreen posle 20 s neaktivnosti, dijalog za izlaz.
- **Reprodukcija:** Media3 (HLS/TS/RTSP), m3u8 sa rezervom na ts, auto-reconnect, info traka sa trenutnom/sledećom emisijom i oznakama streama, audio/titl izbor, format slike, tajmer za gašenje, ponovno učitavanje, FFmpeg audio dekoderi (sopstveni LGPL build, vidi `THIRD_PARTY_NOTICES.md`), ograničenje videa na rezoluciju ekrana (zbog 4K na slabim boxovima), „Kompatibilni prikaz videa“ (TextureView umesto SurfaceView, zbog tragova menija na nekim TCL TV-ima), sigurni DNS (DoH, zbog DNS blokade kod mts-a), spoljni plejer (sistemski izbor: VLC, MX…) za filmove, serije i kanale.
- **Ograničen bafer plejera (0.4.8):** ExoPlayer bafer je ograničen na 1/4 Java heap-a (16–64 MB, na boxu sa 128 MB to je 32 MB). Podrazumevani bafer (do 50 s, ~144 MB) je na 4K HEVC kanalu (20+ Mb/s) punio ceo heap za par sekundi i aplikacija je pucala sa `OutOfMemoryError`.
- **Dolby Vision bez DV dekodera:** `FallbackRenderersFactory` (`player/DolbyVisionFallback.kt`) pušta DV profile 4/7/8 (`dvhe`/`dvh1`) preko HEVC dekodera (osnovni HDR10 sloj) kada uređaj nema Dolby Vision dekoder; Media3 sam to radi samo za profil 8. Profil 5 (IPT boje) se ne dira. Kada nijedan dekoder ne može da prikaže video, plejer prikazuje poruku „format videa nije podržan“ (`REASON_VIDEO_FORMAT`) umesto zvuka preko crnog ekrana; video trake se upisuju u log. Na boxu 4K DV profil 7 film ima sliku, ali u zahtevnim scenama pada na ~13 fps (dekoder boxa ne stiže 4K HEVC 10-bit).
- **Upozorenje za spor dekoder:** `LivePlayer.slowDecoding` se podiže kada je u 10 s stvarnog gledanja (posle 10 s „zagrevanja“ od starta ili baferovanja) ≥40% slika odbačeno zbog kašnjenja i prikazano manje od 20 fps — beIN 4K (8-bit, 50 fps) odbaci do 25% a izgleda tečno, pa ne sme da okine; `SlowDecodingNotice` tada dvaput prikaže toast „uređaj ne stiže… probaj HD verziju“ (jednom po sadržaju). Provereno na boxu na Stingray Now 4K (1605): 463 od 551 slike odbačeno, poruka se pojavila posle ~12 s. Info traka prikazuje rezoluciju iz formata trake (ne 3840×2176 iz dekodera).
- **Watchdog za zastoj:** VOD koji ne krene odbija se tek kada 20 s ne stiže ništa (najviše 2 min čekanja). Spori serveri i MP4 sa indeksom na kraju fajla tako više nisu pogrešno odbijeni kao „nepodržani“.
- **EPG:** XMLTV sa servera ili ručni linkovi; ugrađeni epgshare01 vodiči (≈70 zemalja + servisi) popunjavaju kanale bez programa; uparivanje po ID-ju i po nazivu, uz proveru zemlje (EXYU/EN/DACH grupe), da kanali ne dobiju program na pogrešnom jeziku.
- **VOD:** filmovi i serije, „Nastavi gledanje“, detalji, nastavak od mesta prekida, sledeća epizoda. Sortiranje kroz listu opcija: redosled servera, najnovije dodato, ocena, godina (iz naziva), naziv A–Z. **„Moja lista“** (0.4.7): dugme „+ Moja lista“ / „✓ Moja lista“ na detaljima filma i serije, kategorija „Moja lista“ ispod „Nastavi gledanje“ (najnovije dodato prvo, bez sortiranja, vidi se samo kada nije prazna). Naslov ostaje dok ga korisnik ne ukloni, kao na Netflixu. Tabela `watchlist` (Room v5, AutoMigration 4→5). Testirano na Hisense TV-u. Na Filmovima/Serijama se umesto uživo kanala prikazuje mirna pozadina sa logom (kanal se zaustavlja i vraća na TV ekranima).
- **Program kanala** (`ui/program/`): ceo sačuvani program jednog kanala (3 h unazad, ~3 dana unapred) po danima, fokus na trenutnoj emisiji („Sada“ + napredak), fokusirana emisija prikazuje ceo opis, OK pušta kanal. Otvara se tasterom GUIDE u plejeru, stavkom „Program kanala“ u meniju opcija i strelicom desno na kanalu u listi. Provereno na boxu preko GUIDE; mreža (vodič kanali × vreme) još nije urađena.
- **Rezervna kopija** (Podešavanja → Rezervna kopija, `data/backup/`): JSON fajl preko sistemskog izbora fajlova (SAF) sa favoritima (redosled + naziv), sakrivenim kategorijama, Mojom listom, nastavi gledanje i podešavanjima; bez korisničkog imena i lozinke. Vraćanje traži kanal po ID-ju pa po nazivu. Izvoz proveren na boxu.
- **Samo-ažuriranje** (sideload, `data/update/UpdateManager.kt`, `ui/update/`): vidi „Objava nove verzije“. `REQUEST_INSTALL_PACKAGES` i FileProvider samo u `src/sideload/AndroidManifest.xml`.
- **Catch-up:** ekran arhive za kanale sa `tv_archive`. Kod trenutnog provajdera timeshift URL-ovi vraćaju 404, i u drugim plejerima.
- **Pretraga:** kanali, filmovi i serije u tri kartice sa brojem rezultata; vraćanje fokusa posle Back.
- **Interfejs:** jezici srpski, engleski, nemački, slovački; veličina prikaza 70–100%; automatsko pokretanje; ekran „O aplikaciji“ (verzija iz `BuildConfig`, napomena da je aplikacija samo plejer, Telegram QR `t.me/TimOKDigital`, FFmpeg/LGPL napomena, licence iza dugmeta, tekstovi u `assets/licenses/`).
- **Brzina:** R8 + shrink, baseline profil (`app/src/main/baseline-prof.txt`), streaming JSON za velike VOD liste, logoi dekodirani na veličinu prikaza.

### Odlučeno da se NE radi

- **TMDB / „trending“:** bez TMDB-a i API ključeva; „Trending“ kategorija se ne dodaje.
- **Sortiranje po gledanosti:** Xtream ne daje te podatke, pa ne postoji.
- **GPL biblioteke:** ne koriste se (npr. Jellyfin FFmpeg dekoder); pre bilo koje nove biblioteke sa GPL-om pitati.
- **Kontakt e-mail i politika privatnosti na „O aplikaciji“:** izbačeni, kontakt je samo Telegram.
- **libVLC:** nije u projektu; za problematične streamove postoji spoljni plejer.
- **Logovanje `player_api` odgovora:** nikada, sadrže korisničko ime i lozinku.

### Sledeće na redu

1. **TCL 4K TV:** sačekati povratnu informaciju da li „Kompatibilni prikaz videa“ uklanja tragove menija. Ako uklanja, razmotriti automatsko uključivanje na tom modelu.
2. **Ostatak Faze 2:** prethodni kanal, „poslednje gledano“ za kanale, EPG mreža (vodič), više profila/playlisti, podešavanja dekodera i bafera, prečice na daljinskom (boje, CH+/CH−).
3. **Prebaciti test box na release:** izvoz kopije → deinstalirati staging → instalirati release 0.5.0 → korisnik se prijavi → vratiti kopiju. Posle toga proveriti ažuriranje kada izađe sledeća verzija.
4. **Sitno:** standardno `TvButton` dugme ponekad ne prikazuje fokus kada ga dobije programski pri otvaranju ekrana (zaobiđeno samo na „O aplikaciji“); spoljni plejer za pojedinačnu epizodu (dugo OK).
5. **Faza 3 (opciono):** PIN/roditeljska kontrola, M3U izvor, pauza uživo (timeshift).
