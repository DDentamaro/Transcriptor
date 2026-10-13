package it.donato.trascrittore

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_VIDEO = 10
        private const val REQ_MODEL = 11
        private const val PREFS = "prefs"
    }

    private val languages = listOf(
        "auto" to "Rileva automaticamente",
        "it" to "Italiano",
        "en" to "Inglese",
        "es" to "Spagnolo",
        "fr" to "Francese",
        "de" to "Tedesco",
        "ja" to "Giapponese"
    )

    private lateinit var spModel: Spinner
    private lateinit var txtModelStatus: TextView
    private lateinit var btnModel: Button
    private lateinit var progModel: ProgressBar
    private lateinit var spLang: Spinner
    private lateinit var chkTranslate: CheckBox
    private lateinit var btnPick: Button
    private lateinit var txtVideo: TextView
    private lateinit var btnStart: Button
    private lateinit var progJob: ProgressBar
    private lateinit var txtStatus: TextView
    private lateinit var scroll: ScrollView
    private lateinit var txtTranscript: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button

    private var videoUri: Uri? = null
    private var videoName: String = ""
    private var shownSegments = 0
    private var shownForFile = ""

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spModel = findViewById<Spinner>(R.id.spModel)!!
        txtModelStatus = findViewById<TextView>(R.id.txtModelStatus)!!
        btnModel = findViewById<Button>(R.id.btnModel)!!
        progModel = findViewById<ProgressBar>(R.id.progModel)!!
        spLang = findViewById<Spinner>(R.id.spLang)!!
        chkTranslate = findViewById<CheckBox>(R.id.chkTranslate)!!
        btnPick = findViewById<Button>(R.id.btnPick)!!
        txtVideo = findViewById<TextView>(R.id.txtVideo)!!
        btnStart = findViewById<Button>(R.id.btnStart)!!
        progJob = findViewById<ProgressBar>(R.id.progJob)!!
        txtStatus = findViewById<TextView>(R.id.txtStatus)!!
        scroll = findViewById<ScrollView>(R.id.scroll)!!
        txtTranscript = findViewById<TextView>(R.id.txtTranscript)!!
        btnCopy = findViewById<Button>(R.id.btnCopy)!!
        btnShare = findViewById<Button>(R.id.btnShare)!!

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        spModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Models.list.map { it.label })
        spModel.setSelection(prefs.getInt("model", 1).coerceIn(0, Models.list.size - 1))
        spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("model", pos).apply()
                render()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.second })
        spLang.setSelection(prefs.getInt("lang", 1).coerceIn(0, languages.size - 1))
        spLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("lang", pos).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        chkTranslate.isChecked = prefs.getBoolean("translate", false)
        chkTranslate.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("translate", c).apply() }

        btnModel.setOnClickListener { onModelButton() }
        btnPick.setOnClickListener { pickVideo() }
        btnStart.setOnClickListener { onStartButton() }
        btnCopy.setOnClickListener { copyText() }
        btnShare.setOnClickListener { shareText() }

        savedInstanceState?.getString("videoUri")?.let { setVideo(Uri.parse(it), savedInstanceState.getString("videoName")) }
        handleShareIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let { outState.putString("videoUri", it.toString()) }
        outState.putString("videoName", videoName)
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        render()
    }

    override fun onPause() {
        super.onPause()
        AppState.removeListener(listener)
    }

    // ---------- azioni ----------

    private fun selectedModel(): ModelInfo = Models.list[spModel.selectedItemPosition.coerceAtLeast(0)]

    private fun onModelButton() {
        val m = selectedModel()
        if (m.id == Models.CUSTOM_ID) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_MODEL)
        } else {
            Models.startDownload(this, m, fresh = Models.isReady(this, m))
        }
    }

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        startActivityForResult(i, REQ_VIDEO)
    }

    @Deprecated("API Activity classica")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_VIDEO -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                setVideo(uri, null)
            }
            REQ_MODEL -> {
                Toast.makeText(this, "Importazione modello…", Toast.LENGTH_SHORT).show()
                val app = applicationContext
                Thread {
                    val msg = try {
                        Models.importCustom(app, uri); "Modello importato"
                    } catch (e: Exception) {
                        "Errore: ${e.message}"
                    }
                    runOnUiThread {
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                        render()
                    }
                }.start()
            }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 34) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) setVideo(uri, null)
    }

    private fun setVideo(uri: Uri, name: String?) {
        videoUri = uri
        videoName = name ?: queryName(uri) ?: "video"
        render()
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun onStartButton() {
        if (AppState.isRunning) {
            TranscriptionService.cancel()
            btnStart.isEnabled = false
            btnStart.text = "Annullamento…"
            return
        }
        val uri = videoUri ?: return
        if (!CpuCheck.nativeCompatible()) {
            Toast.makeText(this,
                "Questo telefono non supporta le istruzioni fp16. In app/build.gradle.kts metti useFp16 = false e ricompila.",
                Toast.LENGTH_LONG).show()
            return
        }
        val m = selectedModel()
        if (!Models.isReady(this, m)) {
            Toast.makeText(this, "Prima scarica o importa il modello", Toast.LENGTH_SHORT).show()
            return
        }
        val lang = languages[spLang.selectedItemPosition.coerceAtLeast(0)].first
        shownSegments = 0
        txtTranscript.text = ""
        TranscriptionService.start(this, uri, videoName, Models.file(this, m).path, lang, chkTranslate.isChecked)
    }

    private fun transcriptText(): String = Exporter.toPlainText(AppState.allSegments())

    private fun copyText() {
        val text = transcriptText()
        if (text.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)!!
        cm.setPrimaryClip(ClipData.newPlainText("Trascrizione", text.take(200_000)))
        Toast.makeText(this, "Testo copiato", Toast.LENGTH_SHORT).show()
    }

    private fun shareText() {
        val savedUri = AppState.savedTxtUri
        val send = Intent(Intent.ACTION_SEND)
        if (savedUri != null) {
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_STREAM, savedUri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            val text = transcriptText()
            if (text.isEmpty()) return
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, text.take(200_000))
        }
        startActivity(Intent.createChooser(send, "Condividi trascrizione"))
    }

    // ---------- interfaccia ----------

    private fun render() {
        val m = selectedModel()
        val ready = Models.isReady(this, m)
        val downloadingId = AppState.downloadingModelId
        val running = AppState.isRunning

        // Modello
        when {
            downloadingId == m.id -> {
                val mb = AppState.downloadBytes / 1_000_000
                val totMb = AppState.downloadTotal / 1_000_000
                txtModelStatus.text = if (totMb > 0) "Download… $mb / $totMb MB" else "Download… $mb MB"
                progModel.visibility = View.VISIBLE
                if (AppState.downloadTotal > 0) {
                    progModel.isIndeterminate = false
                    progModel.progress = (AppState.downloadBytes * 100 / AppState.downloadTotal).toInt()
                } else {
                    progModel.isIndeterminate = true
                }
            }
            ready -> {
                txtModelStatus.text = "Pronto ✓ (${Models.file(this, m).length() / 1_000_000} MB)"
                progModel.visibility = View.GONE
            }
            else -> {
                val err = if (AppState.downloadErrorModelId == m.id) AppState.downloadError else null
                txtModelStatus.text = when {
                    err != null -> "Errore download: $err"
                    m.id == Models.CUSTOM_ID -> "Nessun file importato"
                    else -> "Da scaricare (serve internet solo questa volta)"
                }
                progModel.visibility = View.GONE
            }
        }
        btnModel.text = when {
            m.id == Models.CUSTOM_ID -> if (ready) "Sostituisci file" else "Importa file .bin"
            ready -> "Riscarica"
            else -> "Scarica modello"
        }
        btnModel.isEnabled = downloadingId == null && !running
        spModel.isEnabled = !running

        // Video
        txtVideo.text = if (videoUri != null) videoName else "Nessun video scelto"
        btnPick.isEnabled = !running
        spLang.isEnabled = !running
        chkTranslate.isEnabled = !running

        // Avvio / annulla
        if (running) {
            if (btnStart.text.toString() != "Annullamento…") {
                btnStart.text = "Annulla"
                btnStart.isEnabled = true
            }
        } else {
            btnStart.text = "Avvia trascrizione"
            btnStart.isEnabled = videoUri != null && ready && downloadingId == null
        }

        // Stato
        val phase = AppState.phase
        progJob.visibility = if (phase == Phase.IDLE) View.GONE else View.VISIBLE
        progJob.isIndeterminate = phase == Phase.LOADING_MODEL
        progJob.progress = (AppState.progress * 1000).toInt()
        val prefix = when (phase) {
            Phase.DECODING -> "1/2 · "
            Phase.LOADING_MODEL, Phase.TRANSCRIBING -> "2/2 · "
            else -> ""
        }
        txtStatus.text = if (phase == Phase.IDLE) "" else prefix + AppState.detail
        txtStatus.visibility = if (phase == Phase.IDLE) View.GONE else View.VISIBLE

        // Testo: aggiunge solo i segmenti nuovi
        if (shownForFile != AppState.fileName || AppState.segmentCount() < shownSegments) {
            shownForFile = AppState.fileName
            shownSegments = 0
            txtTranscript.text = ""
        }
        val fresh = AppState.segmentsFrom(shownSegments)
        if (fresh.isNotEmpty()) {
            val atBottom = !scroll.canScrollVertically(1)
            val sb = StringBuilder()
            for (s in fresh) {
                val t = s.text.trim()
                if (t.isEmpty()) continue
                if (txtTranscript.length() > 0 || sb.isNotEmpty()) sb.append('\n')
                sb.append('[').append(Exporter.formatClock(s.startMs)).append("] ").append(t)
            }
            txtTranscript.append(sb)
            shownSegments += fresh.size
            if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        if (txtTranscript.length() == 0) {
            txtTranscript.hint = "Il testo apparirà qui mentre viene trascritto."
        }
        val hasText = AppState.segmentCount() > 0
        btnCopy.isEnabled = hasText
        btnShare.isEnabled = hasText
    }
}
