package io.github.gaiser147.claudeoverlay

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.gaiser147.claudeoverlay.cli.AuthManager
import io.github.gaiser147.claudeoverlay.cli.ClaudeSession
import io.github.gaiser147.claudeoverlay.cli.CliEnvironment
import io.github.gaiser147.claudeoverlay.cli.CliInstaller
import io.github.gaiser147.claudeoverlay.cli.LocalProxy
import io.github.gaiser147.claudeoverlay.cli.ModelInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

/** Process-wide singletons shared by the setup screen and the overlay service. */
class AppGraph private constructor(context: Context) {
    val env = CliEnvironment(context)
    val proxy = LocalProxy()
    val installer = CliInstaller(env)
    val auth = AuthManager(env, proxy)
    val settings = OverlaySettings(context.dataStore)

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}

class OverlaySettings(private val store: androidx.datastore.core.DataStore<Preferences>) {
    private val modelKey = stringPreferencesKey("model")
    private val lastSessionKey = stringPreferencesKey("last_session")
    private val screenshotsKey = booleanPreferencesKey("screenshots")
    private val modelsKey = stringPreferencesKey("models")
    private val readAloudKey = booleanPreferencesKey("read_aloud")
    private val controlKey = booleanPreferencesKey("phone_control")
    private val effortKey = stringPreferencesKey("effort")

    val model: Flow<String> = store.data.map { it[modelKey] ?: ClaudeSession.DEFAULT_MODEL }
    val screenshots: Flow<Boolean> = store.data.map { it[screenshotsKey] ?: true }

    suspend fun model(): String = model.first()
    suspend fun lastSession(): String? = store.data.first()[lastSessionKey]
    suspend fun screenshots(): Boolean = screenshots.first()

    /** The CLI's model menu as last reported, or null before the first CLI start. */
    suspend fun models(): List<ModelInfo>? = store.data.first()[modelsKey]?.let { raw ->
        runCatching { ModelInfo.parseList(Json.parseToJsonElement(raw).jsonArray) }.getOrNull()
    }

    suspend fun readAloud(): Boolean = store.data.first()[readAloudKey] ?: false
    suspend fun setReadAloud(value: Boolean) = store.edit { it[readAloudKey] = value }

    /** Whether Claude may operate the phone (tap, type, open apps). Off until the user opts in. */
    suspend fun controlAllowed(): Boolean = store.data.first()[controlKey] ?: false
    suspend fun setControlAllowed(value: Boolean) = store.edit { it[controlKey] = value }

    suspend fun setModels(models: List<ModelInfo>) = store.edit { it[modelsKey] = ModelInfo.toJson(models) }

    suspend fun setModel(value: String) = store.edit { it[modelKey] = value }

    /** Reasoning effort, or null for the model's default. */
    suspend fun effort(): String? = store.data.first()[effortKey]
    suspend fun setEffort(value: String?) = store.edit {
        if (value == null) it.remove(effortKey) else it[effortKey] = value
    }
    suspend fun setScreenshots(value: Boolean) = store.edit { it[screenshotsKey] = value }
    suspend fun setLastSession(value: String?) = store.edit {
        if (value == null) it.remove(lastSessionKey) else it[lastSessionKey] = value
    }
}
