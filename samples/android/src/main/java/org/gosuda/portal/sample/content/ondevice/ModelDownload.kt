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
 * On-demand download of the `.litertlm` model file.
 *
 * The model is ~140 MB (SmolLM2-135M, non-gated — anonymous download works;
 * Gemma repos are gated and need HF auth). It is never fetched implicitly:
 * the user taps "Download model" in the picker, progress is reported through
 * [state], and the file lands in `getExternalFilesDir("models")` — the same
 * directory an `adb push` can target. A partial download is deleted on
 * failure/cancel.
 */
object ModelDownload {

    const val MODEL_URL =
        "https://huggingface.co/litert-community/SmolLM2-135M-Instruct/resolve/main/SmolLM2_135M_Instruct.litertlm"
    const val MODEL_FILE = "SmolLM2_135M_Instruct.litertlm"
    const val MODEL_BYTES = 142_819_328L

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Directory the model is searched in (also the `adb push` target). */
    fun modelDir(context: Context): File =
        context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    fun modelFile(context: Context): File = File(modelDir(context), MODEL_FILE)

    /** Re-scans the directory; call on screen entry so a pushed file counts. */
    fun refresh(context: Context) {
        if (_state.value is State.Downloading) return
        val file = modelFile(context)
        _state.value = if (file.exists() && file.length() > 0) State.Ready(file)
        else State.NotDownloaded
    }

    fun start(context: Context) {
        if (_state.value is State.Downloading) return
        val appContext = context.applicationContext
        scope.launch {
            _state.value = State.Downloading(0, MODEL_BYTES)
            try {
                val file = download(appContext)
                _state.value = State.Ready(file)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "download failed")
            }
        }
    }

    private suspend fun download(context: Context): File = withContext(Dispatchers.IO) {
        val dir = modelDir(context).apply { mkdirs() }
        val target = File(dir, MODEL_FILE)
        val tmp = File(dir, "$MODEL_FILE.part")

        // Disk guard: need the file plus headroom for engine caches.
        if (dir.usableSpace < MODEL_BYTES * 12 / 10) {
            throw IllegalStateException("Not enough storage for a ${MODEL_BYTES / 1_000_000} MB model")
        }

        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var lastReport = 0L
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
