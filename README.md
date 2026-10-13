# Trascrittore

App Android che trascrive l'audio di un video in testo, **offline**, con Whisper (whisper.cpp).
Pensata anche per video lunghi: lavora a blocchi in background, con lo schermo spento.

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

Per andare più veloce, l'app è compilata con le istruzioni **ARMv8.2 fp16**, presenti nei
telefoni dal 2018 circa in poi (Snapdragon 845+, Dimensity, Exynos/Tensor recenti).
Se il tuo telefono è più vecchio, l'app te lo dice quando premi Avvia: apri
`app/build.gradle.kts`, metti `val useFp16 = false` e ricompila. Funzionerà, solo più lenta.

## Come si usa

1. Scegli il modello e premi **Scarica modello** (serve internet solo la prima volta).
   - **Base**: veloce, qualità discreta.
   - **Small**: il miglior compromesso per l'italiano (consigliato).
   - **Large v3 Turbo**: qualità migliore, ma molto più lento.
   - **Importa file .bin**: per usare un modello ggml già scaricato
     (da huggingface.co/ggerganov/whisper.cpp).
2. Scegli la lingua (o "Rileva automaticamente").
   "Traduci in inglese" produce direttamente il testo in inglese
   (funziona con Base e Small; il modello Turbo non sa tradurre).
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
- `cpp/jni_bridge.cpp`: collega Kotlin a whisper.cpp (avanzamento, segmenti, annulla).
- `cpp/whisper.cpp/`: sorgenti di whisper.cpp 1.8.5 (licenza MIT, vedi `LICENSE`),
  inclusi senza i backend GPU che non servono qui.

## Se il build fallisce

- *"NDK not configured"* o *"CMake not found"*: vedi il punto 3.
- Android Studio propone di aggiornare il plugin Gradle (AGP): puoi accettare.
- Incolla l'errore completo a Claude e lo sistemiamo.
