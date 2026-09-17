package org.gosuda.portal.sample

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * SharedPreferences-backed settings for the publish form.
 *
 * `rememberSaveable` only survives Activity recreation — a force-stop or
 * cold start loses every field. These delegates read the persisted value
 * once and write through on every change, so the form restores exactly
 * what the user last configured.
 */
object SampleSettings {
    private const val PREFS = "publish_settings"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default) ?: default

    fun getBoolean(context: Context, key: String, default: Boolean): Boolean =
        prefs(context).getBoolean(key, default)

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun putBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }
}

/** A [MutableState] that writes every change straight to SharedPreferences. */
private class PrefsStringState(
    private val context: Context,
    private val key: String,
    initial: String,
) : MutableState<String> {
    private val backing = mutableStateOf(initial)
    override var value: String
        get() = backing.value
        set(v) {
            backing.value = v
            SampleSettings.putString(context, key, v)
        }
    override fun component1(): String = value
    override fun component2(): (String) -> Unit = { value = it }
}

private class PrefsBooleanState(
    private val context: Context,
    private val key: String,
    initial: Boolean,
) : MutableState<Boolean> {
    private val backing = mutableStateOf(initial)
    override var value: Boolean
        get() = backing.value
        set(v) {
            backing.value = v
            SampleSettings.putBoolean(context, key, v)
        }
    override fun component1(): Boolean = value
    override fun component2(): (Boolean) -> Unit = { value = it }
}

@Composable
fun rememberPersistedString(key: String, default: String): MutableState<String> {
    val context = LocalContext.current
    return remember(key) {
        PrefsStringState(context, key, SampleSettings.getString(context, key, default))
    }
}

@Composable
fun rememberPersistedBoolean(key: String, default: Boolean): MutableState<Boolean> {
    val context = LocalContext.current
    return remember(key) {
        PrefsBooleanState(context, key, SampleSettings.getBoolean(context, key, default))
    }
}
