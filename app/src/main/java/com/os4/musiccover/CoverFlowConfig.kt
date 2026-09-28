package com.os4.musiccover

import org.json.JSONObject

/** Only the on/off choice persists; the Apple Music Web renderer uses fixed parameters. */
object CoverFlowConfig {
    data class Settings(
        val enabled: Boolean = false,
    ) {
        fun json(): String = JSONObject().apply {
            put("enabled", enabled)
        }.toString()
    }

    const val BACKUP_KEY = "coverFlow"

    fun fromJson(raw: String?): Settings {
        val obj = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
        return Settings(enabled = obj.optBoolean("enabled", false))
    }

    @JvmStatic fun normalizedJson(raw: String?): String = fromJson(raw).json()
    @JvmStatic fun defaultJson(): String = Settings().json()

    /** Null preserves the current setting when importing a backup from an older version. */
    fun backupValue(backup: JSONObject): String? =
        backup.optJSONObject(BACKUP_KEY)?.toString()?.let(::normalizedJson)
}
