package org.gosuda.portal.sample.content.ondevice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gosuda.portal.sample.SampleSettings

/**
 * Ollama model catalog and pull management — the desktop analogue of the
 * Android `ModelDownload` (which fetched `.litertlm` files). Instead of
 * downloading raw weights, the sample asks the local Ollama daemon to
 * `pull` a tag; Ollama owns the bytes, GPU use, and resume.
 */
object OllamaModels {

    /** One pullable model tag. [sizeLabel] is the approximate download size. */
    data class ModelSpec(
        val id: String,
        val title: String,
        val tag: String,
        val sizeLabel: String,
        val blurb: String,
    )

    val MODELS: List<ModelSpec> = listOf(
        ModelSpec("smollm2-135m", "SmolLM2 135M", "smollm2:135m", "~92 MB",
            "Tiny and fast — the default. Good enough for the demo."),
        ModelSpec("qwen25-05b", "Qwen2.5 0.5B", "qwen2.5:0.5b", "~398 MB",
            "Small instruct model — quick to pull, decent quality."),
        ModelSpec("llama32-1b", "Llama 3.2 1B", "llama3.2:1b", "~1.3 GB",
            "Meta's compact instruct model — solid quality per byte."),
        ModelSpec("qwen25-15b", "Qwen2.5 1.5B", "qwen2.5:1.5b", "~986 MB",
            "Stronger reasoning; needs ~2 GB free RAM."),
        ModelSpec("gemma3-1b", "Gemma 3 1B", "gemma3:1b", "~815 MB",
            "Google's open 1B — good instruction following."),
        ModelSpec("phi4-mini", "Phi-4 Mini 3.8B", "phi4-mini:latest", "~2.5 GB",
            "Largest in the list — best quality, heaviest load."),
    )

    val DEFAULT_MODEL: ModelSpec = MODELS.first()

    private const val KEY_MODEL = "ollama_selected_model"

    sealed interface State {
        data object NotInstalled : State
        data class Pulling(val bytesDone: Long, val bytesTotal: Long) : State {
            val progress: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
        }
        data class Ready(val tag: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _selected = MutableStateFlow(DEFAULT_MODEL)
    val selected: StateFlow<ModelSpec> = _selected.asStateFlow()

    /** True when the Ollama daemon is reachable. Refreshed by [refresh]. */
    private val _daemonRunning = MutableStateFlow(false)
    val daemonRunning: StateFlow<Boolean> = _daemonRunning.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Restores the persisted selection; call once on app start. */
    fun loadSelection() {
        val id = SampleSettings.getString(KEY_MODEL, "")
        _selected.value = MODELS.firstOrNull { it.id == id } ?: DEFAULT_MODEL
    }

    /** Selects a different model and persists the choice. */
    fun select(spec: ModelSpec) {
        if (_state.value is State.Pulling) return
        if (_selected.value == spec) return
        _selected.value = spec
        SampleSettings.putString(KEY_MODEL, spec.id)
        scope.launch { refresh() }
    }

    /** Re-scans daemon status and whether the selected tag is installed. */
    suspend fun refresh() {
        val running = OllamaClient.isRunning()
        _daemonRunning.value = running
        if (!running) {
            // Kick the managed install/serve path — the daemon may simply
            // not be started yet. The UI reflects OllamaSetup.state.
            OllamaSetup.ensureRunning()
            _state.value = State.NotInstalled
            return
        }
        val installed = OllamaClient.listModels()
        val tag = _selected.value.tag
        val present = installed.any { it == tag || it.startsWith(tag.substringBefore(':')) }
        _state.value = if (present) State.Ready(tag) else State.NotInstalled
    }

    /** True when [spec]'s tag is already pulled. */
    suspend fun isInstalled(spec: ModelSpec): Boolean {
        if (!OllamaClient.isRunning()) return false
        val installed = OllamaClient.listModels()
        return installed.any { it == spec.tag || it.startsWith(spec.tag.substringBefore(':')) }
    }

    /** Pulls the selected model through the daemon, reporting progress. */
    fun pull() {
        if (_state.value is State.Pulling) return
        val spec = _selected.value
        scope.launch {
            // Make sure a daemon exists before asking it to pull.
            if (!OllamaClient.isRunning()) {
                OllamaSetup.ensureRunning()
                val deadline = System.currentTimeMillis() + 30_000
                while (!OllamaClient.isRunning() && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(400)
                }
                if (!OllamaClient.isRunning()) {
                    _state.value = State.Failed("Ollama daemon is not running — install/start it first")
                    return@launch
                }
            }
            _daemonRunning.value = true
            _state.value = State.Pulling(0, 1)
            try {
                OllamaClient.pull(spec.tag) { done, total ->
                    _state.value = State.Pulling(done, total)
                }
                _state.value = State.Ready(spec.tag)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "pull failed")
            }
        }
    }
}
