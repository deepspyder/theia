package com.spektralab.android.data.local

import android.content.Context
import android.util.AtomicFile
import com.spektralab.android.core.model.ExportRecipe
import com.spektralab.android.core.model.ExportSize
import com.spektralab.android.core.model.ExportNaming
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Atomic recipe updates. Old arrays remain readable; unowned JSON fields survive edits. */
object RecipeStore {
    private const val FILE = "recipes.json"

    val DEFAULTS = listOf(
        ExportRecipe("sRGB JPEG 2048", "JPEG", 2048, "sRGB"),
        ExportRecipe("sRGB JPEG 全尺寸", "JPEG", 0, "sRGB"),
        ExportRecipe("Display P3 TIFF 全尺寸", "TIFF", 0, "Display P3"),
        ExportRecipe("sRGB PNG 2048", "PNG", 2048, "sRGB"),
    )

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

    private fun read(context: Context): List<JSONObject>? {
        val store = file(context)
        if (!store.baseFile.exists() && !File(store.baseFile.path + ".bak").exists()) return null
        val bytes = store.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 2 * 1024 * 1024) { "RECIPES_TOO_LARGE" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        require(array.length() <= 100) { "TOO_MANY_RECIPES" }
        return (0 until array.length()).map { array.getJSONObject(it) }.also { values ->
            validate(values.map(::decode))
        }
    }

    private fun decode(json: JSONObject): ExportRecipe {
        require(!json.has("version") || json.get("version") is Number && json.getDouble("version") == 1.0) { "UNSUPPORTED_RECIPE" }
        for (key in listOf("name", "format", "color_space")) require(json.get(key) is String) { "INVALID_RECIPE" }
        for (key in listOf("res", "jpeg_quality")) if (json.has(key)) {
            require(json.get(key) is Number && json.getDouble(key) == json.getInt(key).toDouble()) { "INVALID_RECIPE" }
        }
        for (key in listOf("keep_capture", "keep_location"))
            require(!json.has(key) || json.get(key) is Boolean) { "INVALID_RECIPE" }
        fun optionalObject(key: String): JSONObject? {
            require(!json.has(key) || json.get(key) is JSONObject) { "INVALID_RECIPE" }
            return json.optJSONObject(key)
        }
        return ExportRecipe(json.getString("name"), json.getString("format"), json.getInt("res"),
            json.getString("color_space"), json.optInt("jpeg_quality", 95),
            json.optBoolean("keep_capture", true), json.optBoolean("keep_location", false),
            optionalObject("custom_size")?.let(ExportSize::fromJson),
            optionalObject("naming")?.let(ExportNaming::fromJson) ?: ExportNaming())
    }

    private fun validate(recipes: List<ExportRecipe>) {
        require(recipes.size <= 100) { "TOO_MANY_RECIPES" }
        require(recipes.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == recipes.size) { "DUPLICATE_RECIPE" }
        recipes.forEach {
            require(it.name == it.name.trim() && it.name.isNotEmpty() && it.name.length <= 80 &&
                it.name.none(Char::isISOControl)) { "INVALID_RECIPE_NAME" }
            require(it.format in listOf("JPEG", "PNG", "TIFF") && it.res in 0..65535 &&
                it.colorSpace in listOf("sRGB", "Display P3") && it.jpegQuality in 1..100) { "INVALID_RECIPE" }
        }
    }

    /** A bad file is preserved. The manager reports its error; save refuses to overwrite it. */
    @Synchronized
    fun load(context: Context): List<ExportRecipe> = try {
        read(context)?.map(::decode) ?: DEFAULTS
    } catch (_: Exception) { DEFAULTS }

    @Synchronized
    fun readError(context: Context): Boolean = runCatching { read(context) }.isFailure

    @Synchronized
    fun save(context: Context, recipes: List<ExportRecipe>, renamedFrom: Map<String, String> = emptyMap()) {
        validate(recipes)
        val previous = read(context).orEmpty().associateBy { it.getString("name") }
        val array = JSONArray()
        recipes.forEach { recipe ->
            val old = previous[renamedFrom[recipe.name] ?: recipe.name]
            val json = old?.let { JSONObject(it.toString()) } ?: JSONObject()
            json.put("version", 1).put("name", recipe.name).put("format", recipe.format)
                .put("res", recipe.res).put("color_space", recipe.colorSpace)
                .put("jpeg_quality", recipe.jpegQuality).put("keep_capture", recipe.keepCapture)
                .put("keep_location", recipe.keepLocation)
            fun merge(key: String, value: JSONObject) {
                val merged = json.optJSONObject(key)?.let { JSONObject(it.toString()) } ?: JSONObject()
                value.keys().forEach { merged.put(it, value.get(it)) }
                json.put(key, merged)
            }
            recipe.customSize?.let { merge("custom_size", it.toJson()) } ?: json.remove("custom_size")
            merge("naming", recipe.naming.toJson())
            array.put(json)
        }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2 * 1024 * 1024) { "RECIPES_TOO_LARGE" }
        val store = file(context)
        val stream = store.startWrite()
        try { stream.write(bytes); store.finishWrite(stream) }
        catch (failure: Exception) { store.failWrite(stream); throw failure }
    }
}
