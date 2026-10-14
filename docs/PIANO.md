# Piano di sviluppo — Trascrittore 2.0

Obiettivi: **trascrizione molto più veloce**, **interfaccia moderna**, **riassunti e
schemi** del testo trascritto. Destinazione finale: app pubblica e gratuita
(modello freemium), usata per lezioni, lavoro e qualsiasi altro contenuto.

Ogni fase si chiude con un APK pubblicato nelle Releases e misure reali sul telefono
di riferimento (Google Pixel).

---

## Fase 1 — Misurare (prima di ottimizzare)

- Schermata **Prova velocità**: trascrive un campione audio di 2 minuti incluso
  nell'app con ogni motore/modello e mostra il **fattore di velocità**
  (minuti di audio trascritti per minuto di calcolo).
- Registro dei tempi di ogni trascrizione (durata audio, durata calcolo, modello,
  variante CPU scelta da ggml), visibile in Impostazioni → Diagnostica.
- Uscita: tabella di partenza per Base, Small, Turbo sul Pixel.

## Fase 2 — Velocità

In ordine di costo, ognuna misurata con la Fase 1:

1. **VAD Silero** (già incluso in whisper.cpp 1.8): salta silenzi e pause.
   Guadagno atteso 10–40 % e meno frasi inventate. Modello VAD (~1 MB) dentro l'APK.
2. **Thread sui core grandi**: lettura delle frequenze dei core da
   `/sys/devices/system/cpu`, esclusione dei core "little".
3. **Modelli Q4_0** (riorganizzati da ggml per i8mm/dotprod sui processori ARM
   recenti come i Tensor dei Pixel): quantizzati da noi con la CI e ospitati nelle
   Releases. Si tengono solo se la qualità resta buona su campioni italiani.
4. **Motore Parakeet TDT 0.6B v3** (NVIDIA, via sherpa-onnx, int8): genera il testo
   in un solo passaggio invece che parola per parola; supporta l'italiano e altre
   24 lingue europee, con punteggiatura e tempi. Diventa il motore **"Veloce"**;
   Whisper resta per la traduzione in inglese e le lingue non europee.
   Si adotta solo se la Fase 1 conferma un guadagno netto (atteso: diverse volte).

Esclusi: GPU/NPU (OpenCL, Hexagon), troppo instabili tra telefoni diversi.

## Fase 3 — Interfaccia nuova

- Migrazione a **Jetpack Compose + Material 3**, colori dinamici (Material You),
  tema chiaro/scuro.
- **Home**: pulsante "Nuova trascrizione" + **archivio** delle trascrizioni
  (database Room) con ricerca nel testo.
- **Dettaglio** a schede: *Testo* · *Riassunto* · *Schema* · *Esporta*; tocco su un
  orario → posizione nel video.
- **Avanzamento**: card con percentuale, tempo rimanente, anteprima del testo.
- **Impostazioni**: motore, modello, lingua, IA (locale / cloud), diagnostica.
- Prima configurazione guidata (scelta e download del modello).

## Fase 4 — Riassunti e schemi

### Architettura: motore IA intercambiabile

Un'interfaccia `SummaryEngine` con più implementazioni; l'interfaccia utente e i
formati di uscita non dipendono da quale motore è in uso.

| Motore | Per chi | Costo | Note |
|---|---|---|---|
| **Claude con chiave propria** | sviluppo, utenti esperti | pochi centesimi a lezione (Haiku 5.5 ≈ 0,005 €, Opus 5.5 ≈ 0,20 € per 2 h) | primo da costruire: qualità alta, testo intero in una sola richiesta |
| **Locale (Gemma 3 via llama.cpp)** | tutti, gratis, offline | 0 € | download 1–3 GB; lento su testi lunghi → riassunto a blocchi durante la trascrizione, poi sintesi finale |
| **Cloud tramite server nostro** | pubblico (quota gratuita + Pro) | ~100 €/mese ogni 1.000 utenti attivi con Haiku | necessario per il lancio pubblico: la chiave non può stare nell'APK |

Verso il cloud va solo il testo, mai l'audio, e solo su richiesta esplicita.

### Uscite (modelli selezionabili)

- **Riassunto** breve e lungo
- **Punti chiave**
- **Capitoli con orari**
- **Schema / mappa concettuale** (albero di concetti)
- **Azioni e decisioni** (riunioni): cosa, chi, entro quando
- **Glossario** dei termini tecnici
- **Domande di ripasso / flashcard** (studio)

Preset per tipo di contenuto: *Lezione*, *Riunione*, *Video/Intervista*, *Generico*.
Le uscite sono JSON strutturato (validato), poi convertito nei formati di esportazione.

## Fase 5 — Esportazione

- Cartella per trascrizione: `testo.txt`, `sottotitoli.srt`, `riassunto.md`,
  `schema.md`, `appunti.pdf`, `appunti.docx`, `mappa.png` / `mappa.opml`
  (apribile con le app di mappe mentali).
- Condivisione rapida di ogni singolo file.

## Fase 6 — Lancio pubblico

- `targetSdk` aggiornato al requisito del Play Store; il servizio in primo piano
  passa al tipo `mediaProcessing`, con un limite di 6 ore da gestire per i video molto lunghi.
- Server per l'IA cloud: autenticazione, quota per utente, protezione dagli abusi.
- Informativa privacy, schede dello store, traduzione dell'interfaccia in inglese.
- Modello freemium: trascrizione e IA locale gratis; IA cloud con quota gratuita + Pro.

---

## Decisioni prese

- Secondo motore Parakeet: **sì**, se i test lo confermano.
- IA: **motore intercambiabile**; ordine: Claude con chiave propria → locale gratuito
  → server con quota per il lancio pubblico.
- Contenuti: lezioni e lavoro come riferimento, ma preset per ogni uso.

## Da decidere

- Modello esatto del Pixel (per tarare thread e varianti).
- Modello Claude predefinito per il cloud pubblico (qualità vs costo).
- Prezzo e limiti del piano Pro.
