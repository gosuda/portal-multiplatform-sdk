package org.gosuda.portal.sample.content.ondevice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-demand download of `.litertlm` model files.
 *
 * A catalog of non-gated Hugging Face repos (anonymous download works;
 * Gemma repos are gated and need HF auth). The user picks a model in
 * Settings → '05 / On-device model', taps "Download model" in the picker,
 * progress is reported through [state], and the file lands in
 * `getExternalFilesDir("models")` — the same directory an `adb push` can
 * target. A partial download survives process death as a `.part` file and
 * resumes via HTTP Range on the next attempt.
 *
 * The selected model id is persisted; [refresh] re-scans the selected
 * model's file on screen entry so a file that is already on disk counts.
 */
object ModelDownload {

    /** One downloadable model. [sizeBytes] is the upstream Content-Length. */
    data class ModelSpec(
        val id: String,
        val title: String,
        val repo: String,
        val fileName: String,
        val sizeBytes: Long,
        val blurb: String,
    ) {
        val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
        val sizeLabel: String get() =
            if (sizeBytes >= 1_000_000_000) "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            else "${sizeBytes / 1_000_000} MB"
    }

    val MODELS: List<ModelSpec> = listOf(
        ModelSpec(
            id = "smollm2-135m",
            title = "SmolLM2 135M",
            repo = "litert-community/SmolLM2-135M-Instruct",
            fileName = "SmolLM2_135M_Instruct.litertlm",
            sizeBytes = 142_819_328L,
            blurb = "Tiny and fast — the default. Good enough for the demo.",
        ),
        ModelSpec(
            id = "lfm25-450m",
            title = "LFM2.5-VL 450M",
            repo = "litert-community/LFM2.5-VL-450M",
            fileName = "LFM2.5-VL-450M_int8.litertlm",
            sizeBytes = 563_549_568L,
            blurb = "Liquid vision-language model — small but multimodal.",
        ),
        ModelSpec(
            id = "olmo2-1b",
            title = "OLMo 2 1B",
            repo = "litert-community/OLMo-2-1B-Instruct",
            fileName = "OLMo-2-1B-Instruct_q4_block32_ekv4096.litertlm",
            sizeBytes = 931_241_056L,
            blurb = "Fully open 1B instruct model — solid quality per byte.",
        ),
        ModelSpec(
            id = "qwen25-15b",
            title = "Qwen2.5 1.5B",
            repo = "litert-community/Qwen2.5-1.5B-Instruct",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_597_931_520L,
            blurb = "Stronger reasoning; needs ~2.4 GB free RAM to load.",
        ),
        ModelSpec(
            id = "smollm2-17b",
            title = "SmolLM2 1.7B",
            repo = "litert-community/SmolLM2-1.7B-Instruct",
            fileName = "SmolLM2-1_7B-Instruct_dynamic_wi8_afp32.litertlm",
            sizeBytes = 1_730_949_280L,
            blurb = "Largest in the list — best quality, heaviest load.",
        ),
    )

    val DEFAULT_MODEL: ModelSpec = MODELS.first()

    private const val PREFS = "ondevice_model"
    private const val KEY_MODEL = "selected_model"

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val bytesDone: Long, val bytesTotal: Long) : State {
            val progress: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
        }
        data class Ready(val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _selected = MutableStateFlow(DEFAULT_MODEL)
    val selected: StateFlow<ModelSpec> = _selected.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Directory the models are searched in (also the `adb push` target). */
    fun modelDir(context: Context): File =
        context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    fun modelFile(context: Context, spec: ModelSpec = _selected.value): File =
        File(modelDir(context), spec.fileName)

    /** True when [spec]'s file is already on disk. */
    fun isDownloaded(context: Context, spec: ModelSpec): Boolean {
        val f = modelFile(context, spec)
        return f.exists() && f.length() > 0
    }

    /** Restores the persisted selection; call once on app start. */
    fun loadSelection(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
        _selected.value = MODELS.firstOrNull { it.id == id } ?: DEFAULT_MODEL
    }

    /**
     * Selects a different model and persists the choice. The engine picks it
     * up on the next publish (or restarts if it is already running).
     */
    fun select(context: Context, spec: ModelSpec) {
        if (_state.value is State.Downloading) return
        if (_selected.value == spec) return
        _selected.value = spec
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, spec.id).apply()
        refresh(context)
    }

    /** Re-scans the selected model's file; call on screen entry. */
    fun refresh(context: Context) {
        if (_state.value is State.Downloading) return
        val file = modelFile(context)
        _state.value = if (file.exists() && file.length() > 0) State.Ready(file)
        else State.NotDownloaded
    }

    fun start(context: Context) {
        if (_state.value is State.Downloading) return
        val appContext = context.applicationContext
        val spec = _selected.value
        scope.launch {
            _state.value = State.Downloading(0, spec.sizeBytes)
            try {
                val file = download(appContext, spec)
                _state.value = State.Ready(file)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "download failed")
            }
        }
    }

    private suspend fun download(context: Context, spec: ModelSpec): File = withContext(Dispatchers.IO) {
        val dir = modelDir(context).apply { mkdirs() }
        val target = File(dir, spec.fileName)
        val tmp = File(dir, "${spec.fileName}.part")

        // Disk guard: need the file plus headroom for engine caches.
        if (dir.usableSpace < spec.sizeBytes * 12 / 10) {
            throw IllegalStateException("Not enough storage for a ${spec.sizeLabel} model")
        }

        // Resume a partial download left by a process kill: Hugging Face
        // honors Range, so a .part file continues instead of restarting.
        var done = tmp.length().takeIf { tmp.exists() } ?: 0L
        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (done > 0) setRequestProperty("Range", "bytes=$done-")
        }
        try {
            // A 200 means the server ignored Range — restart from scratch.
            val append = done > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (done > 0 && !append) done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp, append).use { out ->
                    val total = if (append) done + conn.contentLengthLong
                        else conn.contentLengthLong.takeIf { it > 0 } ?: spec.sizeBytes
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    _state.value = State.Downloading(done, total)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 4 * 1024 * 1024) {
                            lastReport = done
                            _state.value = State.Downloading(done, total)
                        }
                    }
                    _state.value = State.Downloading(done, total)
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("Could not finalize model file")
        }
        target
    }
}
