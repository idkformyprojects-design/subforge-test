package ai.subforge.universal.core

import android.content.Context

/** Small settings store: Gemini key, model and the last chosen language. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("subforge", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }

    /** Model names change over time, so the user can edit this in Settings. */
    var model: String
        get() = (sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(v) { sp.edit().putString("model", v.trim()).apply() }

    /** Models to use when the main one is busy, comma separated, best first. */
    var backupModels: String
        get() {
            val v = sp.getString("backup_models", DEFAULT_BACKUPS) ?: DEFAULT_BACKUPS
            // People who saved Settings with the old default get the new, longer list automatically.
            return if (v.trim() == OLD_DEFAULT_BACKUPS) DEFAULT_BACKUPS else v
        }
        set(v) { sp.edit().putString("backup_models", v.trim()).apply() }

    fun backupList(): List<String> =
        backupModels.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != model }.distinct()

    /** Second listen by the best model to correct mistakes. About twice the requests. */
    var doubleCheck: Boolean
        get() = sp.getBoolean("double_check", false)
        set(v) { sp.edit().putBoolean("double_check", v).apply() }

    /** Set when the user opened Google's key page, so the app checks the clipboard when they come back (15 min). */
    var awaitingKeyUntil: Long
        get() = sp.getLong("await_key", 0L)
        set(v) { sp.edit().putLong("await_key", v).apply() }

    /** Clean up the sound (filter rumble/hiss, lift quiet speech) before sending it. */
    var cleanSound: Boolean
        get() = sp.getBoolean("clean_sound", true)
        set(v) { sp.edit().putBoolean("clean_sound", v).apply() }

    /** Last "about this video" text, so it is not lost if the job is started again. */
    var about: String
        get() = sp.getString("about", "") ?: ""
        set(v) { sp.edit().putString("about", v).apply() }

    var askedBattery: Boolean
        get() = sp.getBoolean("asked_battery", false)
        set(v) { sp.edit().putBoolean("asked_battery", v).apply() }

    var languageIndex: Int
        get() = sp.getInt("lang_idx", 1)
        set(v) { sp.edit().putInt("lang_idx", v).apply() }

    companion object {
        /** "-latest" names always point to Google's current model, so they do not get retired. */
        const val DEFAULT_MODEL = "gemini-flash-latest"
        /**
         * Google counts the free daily limit separately for each model, so every extra free model in this list
         * adds more free requests per day. Names that do not exist for a key are skipped automatically.
         */
        const val DEFAULT_BACKUPS =
            "gemini-flash-latest, gemini-3.6-flash, gemini-flash-lite-latest, gemini-3.5-flash-lite, gemini-3.1-flash-lite"
        private const val OLD_DEFAULT_BACKUPS = "gemini-flash-latest, gemini-flash-lite-latest"
    }
}

/** Target languages shown in the picker. "Original" means transcribe without translating. */
object Languages {
    class Lang(val name: String, val iso639_2: String)

    val ALL: List<Lang> = listOf(
        Lang("Original language (no translation)", "und"),
        Lang("English", "eng"), Lang("Bengali", "ben"), Lang("Hindi", "hin"), Lang("Urdu", "urd"),
        Lang("Arabic", "ara"), Lang("Assamese", "asm"), Lang("Gujarati", "guj"), Lang("Kannada", "kan"),
        Lang("Malayalam", "mal"), Lang("Marathi", "mar"), Lang("Nepali", "nep"), Lang("Odia", "ori"),
        Lang("Punjabi", "pan"), Lang("Tamil", "tam"), Lang("Telugu", "tel"), Lang("Sinhala", "sin"),
        Lang("Persian", "per"), Lang("Turkish", "tur"), Lang("Hebrew", "heb"),
        Lang("Chinese (Simplified)", "chi"), Lang("Chinese (Traditional)", "chi"), Lang("Japanese", "jpn"),
        Lang("Korean", "kor"), Lang("Thai", "tha"), Lang("Vietnamese", "vie"), Lang("Indonesian", "ind"),
        Lang("Malay", "may"), Lang("Filipino", "fil"), Lang("Burmese", "bur"),
        Lang("Spanish", "spa"), Lang("Portuguese", "por"), Lang("French", "fre"), Lang("German", "ger"),
        Lang("Italian", "ita"), Lang("Dutch", "dut"), Lang("Polish", "pol"), Lang("Russian", "rus"),
        Lang("Ukrainian", "ukr"), Lang("Greek", "gre"), Lang("Swedish", "swe"), Lang("Romanian", "rum"),
        Lang("Swahili", "swa"), Lang("Amharic", "amh"), Lang("Hausa", "hau"), Lang("Yoruba", "yor"),
    )

    fun get(index: Int): Lang = ALL[index.coerceIn(0, ALL.size - 1)]
    fun isOriginal(lang: Lang): Boolean = lang.iso639_2 == "und"
}

/** Recognises a Google AI Studio API key in copied text, so the user never has to type it. */
object KeyHelper {
    // Google API keys are "AIza" followed by 35 letters, digits, "-" or "_".
    private val KEY = Regex("AIza[0-9A-Za-z_\\-]{35}")

    const val SETUP_URL = "https://aistudio.google.com/apikey"

    fun find(text: CharSequence?): String? = text?.let { KEY.find(it)?.value }

    /** "AIzaSy…k9Q" — enough to recognise it without showing the whole key. */
    fun mask(key: String): String = if (key.length > 10) key.take(6) + "…" + key.takeLast(3) else "…"
}
