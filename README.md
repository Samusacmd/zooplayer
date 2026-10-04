# ZooPlayer Android 1.1.1

Porting per Android (telefono, tablet, Android TV, Fire TV) di ZooPlayer 2.8.0 desktop.
Legge gli stessi JSON generati dal Paste Tool (`name` / `urlList` / `url` / `imageUrl`).

## Changelog
- **1.1.1** Pulsanti del player disegnati come icone vettoriali: prima erano simboli
  che alcuni telefoni (es. Xiaomi) mostravano come emoji arancioni di dimensioni diverse.
- **1.1.0** Schermo intero (barre di sistema nascoste, video ruotato in orizzontale);
  comandi del video sovrapposti che spariscono dopo 4 s (tocco o tasto del telecomando
  li fa ricomparire); comandi compatti in orizzontale; audio in orizzontale con cover e
  comandi affiancati; margini corretti sotto barre e notch (Android 15).
- **1.0.0** Prima versione Android, porting di ZooPlayer desktop 2.8.0.

## Versionamento (SemVer)
La versione è in `app/build.gradle.kts`, riga `val semVer = "1.0.0"`.
- `versionName` = semVer
- `versionCode` = numero di esecuzione di GitHub Actions (`GITHUB_RUN_NUMBER`)
- l'APK esce come `ZooPlayer_v<semVer>.apk`

## Build su GitHub
1. Crea un repository (meglio **privato**) e carica tutta la cartella con **"Upload files"**.
2. La build parte da sola a ogni push. L'APK si scarica da **Actions → ultima esecuzione → Artifacts**.
3. Con un tag `v1.0.0` l'APK viene pubblicato anche nelle **Releases** (comodo da scaricare sulla TV).

## Firma (da fare una volta, prima di installare)
Senza firma fissa ogni build ha una chiave diversa: Android rifiuta l'aggiornamento e
devi disinstallare (perdendo preferiti, ascoltati e indice).
1. Actions → **Genera keystore (da eseguire UNA volta)** → Run workflow.
2. Scarica l'artifact e copia i valori in **Settings → Secrets and variables → Actions**:
   - `KEYSTORE_BASE64` ← contenuto di KEYSTORE_BASE64.txt
   - `KEYSTORE_PASSWORD` e `KEY_PASSWORD` ← contenuto di KEYSTORE_PASSWORD_e_KEY_PASSWORD.txt
   - `KEY_ALIAS` ← `zooplayer`
3. Conserva `zooplayer.jks` e la password in un posto sicuro, poi **cancella l'artifact**.

## Cosa c'è (rispetto alla 2.8.0)
| Funzione 2.8.0 | Android 1.0.0 |
|---|---|
| Navigazione elenchi annidati, link pastebin → raw | ✔ |
| Riproduzione video/audio (anche WMA, FLV) | ✔ libVLC |
| Stream radio (es. R101) riconosciuti come stream | ✔ |
| ±10 s, precedente/successivo, avanzamento automatico | ✔ |
| Viste lista / elenco / box cover | ✔ |
| Cover elenchi dal JSON, cover brano dal file audio | ✔ |
| Preferiti ★, ascoltati ✔ | ✔ |
| Ripristino sessione e posizione | ✔ |
| Ricerca su indice SQLite costruito una volta (min. 3 caratteri) | ✔ |
| Download singolo e "Scarica tutto" con avviso | ✔ in Download/ZooPlayer |
| JSON locali | ✔ solo con link assoluti |
| Riproduzione in background / schermo spento | ✔ (nuova) |
| Tasti multimediali telecomando | ✔ (nuova) |
| Tooltip dopo 3 s | ✘ non esiste l'hover su Android |
| Verifica link morti | ✘ non ancora |
| Installazione dipendenze / VLC | non serve: libVLC è dentro l'APK |

## Log
Menu ⋮ → **Condividi log**. File: `Android/data/it.sam.zooplayer/files/zooplayer.log`.

## Se la build fallisce su libVLC
Se Gradle non trova `org.videolan.android:libvlc-all:3.6.0`, cambia la versione in
`app/build.gradle.kts` con l'ultima 3.x presente su Maven Central (non usare le 4.0.0-eap).
