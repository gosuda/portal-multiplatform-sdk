package org.gosuda.portal.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.util.prefs.Preferences

/**
 * Preferences-backed settings for the publish form (desktop analogue of the
 * Android SharedPreferences store). Every field writes through on change so
 * the form restores exactly what the user last configured across restarts.
 */
object SampleSettings {
    private val prefs: Preferences =
        Preferences.userRoot().node("org/gosuda/portal/sample/publish")

    fun getString(key: String, default: String): String = prefs.get(key, default)
    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun putString(key: String, value: String) { prefs.put(key, value) }
    fun putBoolean(key: String, value: Boolean) { prefs.putBoolean(key, value) }
}

private class PrefsStringState(private val key: String, initial: String) : MutableState<String> {
    private val backing = mutableStateOf(initial)
    override var value: String
        get() = backing.value
        set(v) { backing.value = v; SampleSettings.putString(key, v) }
    override fun component1(): String = value
    override fun component2(): (String) -> Unit = { value = it }
}

private class PrefsBooleanState(private val key: String, initial: Boolean) : MutableState<Boolean> {
    private val backing = mutableStateOf(initial)
    override var value: Boolean
        get() = backing.value
        set(v) { backing.value = v; SampleSettings.putBoolean(key, v) }
    override fun component1(): Boolean = value
    override fun component2(): (Boolean) -> Unit = { value = it }
}

@Composable
fun rememberPersistedString(key: String, default: String): MutableState<String> =
    remember(key) { PrefsStringState(key, SampleSettings.getString(key, default)) }

@Composable
fun rememberPersistedBoolean(key: String, default: Boolean): MutableState<Boolean> =
    remember(key) { PrefsBooleanState(key, SampleSettings.getBoolean(key, default)) }
