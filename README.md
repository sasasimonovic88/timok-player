# TIM-OK Player

IPTV plejer za **Android TV, Google TV i Fire TV**, napravljen za TIM-OK Digital. Pravljen je za daljinski upravljač (D-pad) i televizor; telefon i tablet nisu cilj.

> Aplikacija je samo plejer: ne sadrži kanale, filmove ni bilo kakav sadržaj. Korisnik unosi svoj Xtream Codes nalog (server, korisničko ime, lozinka).

*English: TIM-OK Player is an IPTV player for Android TV / Google TV / Fire TV, built for remote-control use. It ships no content; users sign in with their own Xtream Codes account.*

## Funkcije

- **TV uživo:** grupe sa servera, favoriti sa ručnim redosledom, sakrivanje kategorija, unos broja kanala iz plejera i iz menija, automatsko ponovno povezivanje.
- **TV program (EPG):** XMLTV sa servera ili ručni linkovi, ugrađeni rezervni vodiči, trenutna i sledeća emisija na listi i info traci, ceo program kanala po danima (taster GUIDE).
- **Filmovi i serije:** kategorije, sortiranje, detalji, „Nastavi gledanje“, nastavak od mesta prekida, sledeća epizoda, „Moja lista“.
- **Arhiva (catch-up)** za kanale koji je imaju i **pretraga** kanala, filmova i serija.
- **Reprodukcija (Media3/ExoPlayer):** HLS, MPEG-TS i RTSP, izbor audio traga i titla, format slike, tajmer za gašenje, spoljni plejer (VLC, MX…), sopstveni LGPL FFmpeg audio dekoderi (AC3, E-AC3, MP2…), Dolby Vision preko HEVC osnovnog sloja na uređajima bez DV dekodera, upozorenje kada uređaj ne stiže da prikaže video tečno.
- **Interfejs:** srpski, engleski, nemački i slovački; veličina prikaza; automatsko pokretanje sa poslednjim kanalom.
- **Privatnost:** bez analitike, bez reklama, bez crash-reportera trećih strana. Odgovori servera sa korisničkim podacima se ne upisuju u log.

## Tehnologija

Kotlin, Jetpack Compose za TV (`androidx.tv`), Media3 (ExoPlayer), OkHttp + Retrofit + kotlinx.serialization, Room, DataStore, Hilt, Coil, WorkManager. minSdk 23 (Android 6), targetSdk 36.

## Build

Potreban je **JDK 17+** (npr. JDK koji dolazi uz Android Studio) i Android SDK.

1. U korenu projekta napravi `local.properties` (ne ide u git):

   ```properties
   sdk.dir=/putanja/do/Android/sdk

   # Samo sideload varijanta: unapred upisan server (prazno = korisnik ga unosi) i JSON za proveru ažuriranja
   timok.server=
   timok.updateUrl=

   # Potpisivanje release verzije (ključ ne ide u git)
   timok.signing.storeFile=signing/timok-release.jks
   timok.signing.storePassword=...
   timok.signing.keyAlias=...
   timok.signing.keyPassword=...
   ```

2. Pokreni build:

   ```bash
   ./gradlew assembleSideloadDebug      # razvoj
   ./gradlew assembleSideloadStaging    # release brzina, debug ključ (za testiranje na uređaju)
   ./gradlew assembleSideloadRelease    # distribucija, potpisano release ključem
   ```

### Varijante

| Varijanta | Opis |
|---|---|
| `sideload` | Server može biti unapred upisan, uključena provera ažuriranja sa sopstvenog servera. |
| `play` | Bez unapred upisanog servera i bez samostalnog ažuriranja (pravila Google Play-a). |

`staging` i `release` imaju različit potpis, pa se ne mogu instalirati jedan preko drugog bez brisanja podataka aplikacije.

## Licence

Biblioteke trećih strana i FFmpeg (LGPL, sopstveni build, skripta u `ffmpeg/native/`) navedeni su u [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Projekat ne koristi GPL biblioteke.

Kontakt: Telegram [t.me/TimOKDigital](https://t.me/TimOKDigital)
