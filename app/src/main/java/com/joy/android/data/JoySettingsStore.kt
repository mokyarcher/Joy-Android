package com.joy.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.joyDataStore: DataStore<Preferences> by preferencesDataStore(name = "joy_settings")

class JoySettingsStore(private val context: Context) {
    private object Keys {
        val presetId = stringPreferencesKey("preset_id")
        val baseUrl = stringPreferencesKey("base_url")
        val apiKey = stringPreferencesKey("api_key")
        val model = stringPreferencesKey("model")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val imageCaptureMode = stringPreferencesKey("image_capture_mode")
        val visionPresetId = stringPreferencesKey("vision_preset_id")
        val visionBaseUrl = stringPreferencesKey("vision_base_url")
        val visionApiKey = stringPreferencesKey("vision_api_key")
        val visionModel = stringPreferencesKey("vision_model")
        val visionImagePolicy = stringPreferencesKey("vision_image_policy")
        val autoStartOverlay = booleanPreferencesKey("auto_start_overlay")
        val overlayCoreColor = intPreferencesKey("overlay_core_color")
        val overlaySatelliteColor = intPreferencesKey("overlay_satellite_color")
    }

    val settings: Flow<JoySettings> = context.joyDataStore.data.map { prefs ->
        val rawUrl = prefs[Keys.baseUrl]
        val rawModel = prefs[Keys.model]
        // 旧版默认 OpenAI：未真正配置过时迁移到商汤（与电脑端一致）
        val migrated = shouldMigrateLegacyOpenAi(rawUrl, rawModel, prefs[Keys.apiKey])
        val baseUrl = if (migrated) {
            JoySettings.DEFAULT_BASE_URL
        } else {
            rawUrl ?: JoySettings.DEFAULT_BASE_URL
        }
        val model = if (migrated) {
            JoySettings.DEFAULT_MODEL
        } else {
            rawModel ?: JoySettings.DEFAULT_MODEL
        }
        val presetId = prefs[Keys.presetId]
            ?: ProviderPresets.matchPresetId(baseUrl, model)

        val visionBaseUrl = prefs[Keys.visionBaseUrl] ?: ""
        val visionModel = prefs[Keys.visionModel]
            ?: legacyVisionModelDefault(prefs.contains(Keys.visionBaseUrl))
        val visionPresetId = prefs[Keys.visionPresetId]
            ?: ProviderPresets.matchPresetId(
                visionBaseUrl.ifBlank { baseUrl },
                visionModel,
            )

        JoySettings(
            presetId = presetId,
            baseUrl = baseUrl,
            apiKey = prefs[Keys.apiKey] ?: "",
            model = model,
            systemPrompt = prefs[Keys.systemPrompt] ?: JoySettings.DEFAULT_SYSTEM_PROMPT,
            imageCaptureMode = ImageCaptureMode.fromId(prefs[Keys.imageCaptureMode]),
            visionPresetId = visionPresetId,
            visionBaseUrl = visionBaseUrl,
            visionApiKey = prefs[Keys.visionApiKey] ?: "",
            visionModel = visionModel,
            visionImagePolicy = VisionImagePolicy.fromId(prefs[Keys.visionImagePolicy]),
            autoStartOverlay = prefs[Keys.autoStartOverlay] ?: false,
            overlayCoreColorArgb = prefs[Keys.overlayCoreColor]
                ?: JoySettings.DEFAULT_OVERLAY_CORE_COLOR,
            overlaySatelliteColorArgb = prefs[Keys.overlaySatelliteColor]
                ?: JoySettings.DEFAULT_OVERLAY_SATELLITE_COLOR,
        )
    }

    suspend fun save(settings: JoySettings) {
        context.joyDataStore.edit { prefs ->
            prefs[Keys.presetId] = settings.presetId.trim().ifEmpty { JoySettings.DEFAULT_PRESET_ID }
            prefs[Keys.baseUrl] = settings.baseUrl.trim()
            prefs[Keys.apiKey] = settings.apiKey.trim()
            prefs[Keys.model] = settings.model.trim()
            prefs[Keys.systemPrompt] = settings.systemPrompt.trim().ifEmpty {
                JoySettings.DEFAULT_SYSTEM_PROMPT
            }
            prefs[Keys.imageCaptureMode] = settings.imageCaptureMode.id
            prefs[Keys.visionPresetId] = settings.visionPresetId.trim()
                .ifEmpty { JoySettings.DEFAULT_VISION_PRESET_ID }
            prefs[Keys.visionBaseUrl] = settings.visionBaseUrl.trim()
            prefs[Keys.visionApiKey] = settings.visionApiKey.trim()
            prefs[Keys.visionModel] = settings.visionModel.trim().ifEmpty {
                JoySettings.DEFAULT_VISION_MODEL
            }
            prefs[Keys.visionImagePolicy] = settings.visionImagePolicy.id
            prefs[Keys.autoStartOverlay] = settings.autoStartOverlay
            prefs[Keys.overlayCoreColor] = settings.overlayCoreColorArgb
            prefs[Keys.overlaySatelliteColor] = settings.overlaySatelliteColorArgb
        }
    }

    /**
     * 尚未写过视觉 Base URL 的老安装：继续用商汤视觉模型名，避免空 URL + glm 名错配。
     */
    private fun legacyVisionModelDefault(hasVisionBaseUrlKey: Boolean): String =
        if (hasVisionBaseUrlKey) {
            JoySettings.DEFAULT_VISION_MODEL
        } else {
            "sensenova-6.7-flash-lite"
        }

    private fun shouldMigrateLegacyOpenAi(
        baseUrl: String?,
        model: String?,
        apiKey: String?,
    ): Boolean {
        if (!apiKey.isNullOrBlank()) return false
        val url = (baseUrl ?: "").trim().trimEnd('/')
        val m = (model ?: "").trim()
        val looksLegacy =
            url == "https://api.openai.com/v1" &&
                (m.isEmpty() || m == "gpt-4o-mini")
        return looksLegacy || (baseUrl == null && model == null)
    }
}
