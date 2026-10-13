# Trascrittore

App Android che trascrive l'audio di un video in testo, **offline**, con Whisper (whisper.cpp).
Pensata anche per video lunghi: lavora a blocchi in background, con lo schermo spento.

## Scaricare l'APK già pronto

Ogni versione pubblicata è nella pagina **Releases** del repository GitHub:
scarica `Trascrittore-x.y.apk` sul telefono e aprilo per installarlo
(la prima volta Android chiede di consentire l'installazione da quella app).

Ogni push compila anche un APK di prova: lo trovi in *Actions → Build APK →*
l'ultima esecuzione *→ Artifacts*.

## Come compilarla (una volta sola, sul PC)

1. Installa **Android Studio** (versione recente).
2. Estrai lo zip e apri la cartella `Trascrittore` con *File → Open*.
3. Se Android Studio lo chiede, installa **NDK** e **CMake 3.22.1**
   (*Tools → SDK Manager → SDK Tools*, spunta "NDK (Side by side)" e "CMake").
4. Collega il telefono via USB con il **debug USB** attivo
   (Impostazioni → Info telefono → tocca 7 volte "Numero build" → Opzioni sviluppatore → Debug USB).
5. Premi **Run ▶**. Il primo build richiede qualche minuto: compila whisper.cpp.

In alternativa: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*, poi copi
`app/build/outputs/apk/debug/app-debug.apk` sul telefono e lo installi.

Serve un telefono **a 64 bit** (arm64). L'emulatore x86 non è supportato.

L'app contiene più versioni del motore di calcolo (ARMv8.0, dotprod, fp16, i8mm,
SVE2, SME) e all'avvio usa la più veloce che il processore supporta: funziona su
qualsiasi telefono a 64 bit senza ricompilare, e i telefoni recenti vanno più veloci.

## Come si usa

1. Scegli il modello e premi **Scarica modello** (serve internet solo la prima volta).
   - **Base**: veloce, qualità discreta.
   - **Small**: il miglior compromesso per l'italiano (consigliato).
   - **Large v3 Turbo**: qualità migliore, ma molto più lento.
   - **Importa file .bin**: per usare un modello ggml già scaricato
     (da huggingface.co/ggerganov/whisper.cpp).
2. Scegli la lingua (o "Rileva automaticamente").
   Con "Rileva automaticamente" la lingua viene riconosciuta sui primi minuti
   e poi resta fissa per tutto il video.
   "Traduci in inglese" produce direttamente il testo in inglese
   (funziona con Base e Small; con Turbo l'opzione è disattivata perché non sa tradurre).
3. **Scegli video**, oppure dalla galleria usa *Condividi → Trascrittore*.
4. **Avvia trascrizione**. Puoi uscire dall'app o spegnere lo schermo:
   la notifica mostra l'avanzamento e ha il tasto Annulla.

Alla fine trovi in **Download/Trascrizioni**:
- `nome-video.txt`: il testo continuo, seguito dalla versione con i tempi `[mm:ss]`
- `nome-video.srt`: sottotitoli pronti per un editor video

Se annulli o qualcosa va storto, la parte già trascritta viene salvata comunque.

## Tempi indicativi

Dipendono molto dal telefono. Come ordine di grandezza, su un telefono di fascia
medio-alta un'ora di video richiede circa 10-20 minuti con *Base* e 30-60 minuti
con *Small*. Il telefono si scalda: meglio tenerlo in carica per i video lunghi.

## Come funziona

- `AudioDecoder.kt`: estrae l'audio con i decoder del telefono (MediaCodec) e lo
  converte in 16 kHz mono su un file temporaneo, senza caricare tutto in memoria.
- `TranscriptionService.kt`: servizio in primo piano con wake lock; divide l'audio
  in blocchi da 5 minuti tagliati nel punto più silenzioso, così non spezza le parole.
  La fine di ogni blocco viene passata a Whisper come contesto del blocco successivo
  (nomi e stile restano coerenti).
- `TextFilter.kt`: scarta le frasi che Whisper "inventa" nei silenzi
  (es. "Sottotitoli creati dalla comunità Amara.org") e le ripetizioni in loop.
- `cpp/jni_bridge.cpp`: collega Kotlin a whisper.cpp (avanzamento, segmenti, annulla).
- `cpp/whisper.cpp/`: sorgenti di whisper.cpp 1.8.5 (licenza MIT, vedi `LICENSE`),
  inclusi senza i backend GPU che non servono qui.

## Firma e aggiornamenti (GitHub Actions)

Per installare una nuova versione sopra la vecchia, tutti gli APK devono essere
firmati con la stessa chiave. Una volta sola:

1. Crea la chiave (sul PC, con Java installato) e **conservala**: se la perdi,
   per aggiornare dovrai disinstallare l'app.
   ```
   keytool -genkeypair -v -keystore trascrittore.jks -alias trascrittore \
           -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 trascrittore.jks > trascrittore.jks.b64
   ```
2. Nel repository GitHub: *Settings → Secrets and variables → Actions → New repository secret*:
   - `KEYSTORE_BASE64`: il contenuto di `trascrittore.jks.b64`
   - `KEYSTORE_PASSWORD` e `KEY_PASSWORD`: le password scelte
   - `KEY_ALIAS`: `trascrittore`

Senza questi secrets la compilazione funziona lo stesso, ma ogni APK ha una firma
diversa e va installato dopo aver disinstallato il precedente.

Per pubblicare una versione: aumenta `versionName` in `app/build.gradle.kts`
(es. `"1.2"`) e porta la modifica su `main`. La compilazione crea la release `v1.2`
con l'APK allegato. Ogni push su `main` con la stessa versione aggiorna l'APK della release.

## Se il build fallisce

- *"NDK not configured"* o *"CMake not found"*: vedi il punto 3.
- Android Studio propone di aggiornare il plugin Gradle (AGP): puoi accettare.
- Incolla l'errore completo a Claude e lo sistemiamo.
