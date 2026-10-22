package com.notionwidget.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class NotionException(val code: Int, message: String) : Exception(message)

/** A database property; [options] are filled in for select, multi-select and status. */
data class PropertyInfo(
    val name: String,
    val type: String,
    val options: List<String> = emptyList(),
    /** For status properties, the first option of the "Complete" group. */
    val completeOption: String? = null,
)

/**
 * Minimal Notion API client.
 * Since version 2025-09-03, databases are queried through "data sources".
 */
class NotionApi(private val token: String) {

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Returns the first data source of a database. */
    suspend fun firstDataSourceId(databaseId: String): String {
        val db = call("GET", "/databases/$databaseId")
        val sources = db["data_sources"] as? JsonArray
        return sources?.firstOrNull().obj()?.get("id").str()
            ?: throw NotionException(0, "The database has no data sources (did you connect it to the integration?)")
    }

    /**
     * Tasks whose status is NOT [doneValue], sorted by [sorts].
     * If [extraFilterJson] isn't empty, it's combined with an "and" (to mimic a Notion
     * view's filter, since the API can't query views directly).
     */
    suspend fun queryPending(
        dataSourceId: String,
        dateProperty: String,
        statusProperty: String,
        statusType: String,
        doneValue: String,
        extraFilterJson: String = "",
        extraProperties: List<String> = emptyList(),
        titleCache: MutableMap<String, String?> = mutableMapOf(),
        sorts: List<SortSpec> = listOf(SortSpec(dateProperty)),
    ): List<Task> {
        val statusFilter = buildJsonObject {
            put("property", statusProperty)
            putJsonObject(statusType) { put("does_not_equal", doneValue) }
        }
        val extraFilter = extraFilterJson.takeIf { it.isNotBlank() }?.let {
            runCatching { AppJson.parseToJsonElement(expandDates(it)).jsonObject }.getOrNull()
                ?: throw NotionException(0, "A view filter isn't valid JSON")
        }
        val clientCheck = ClientFilter.needsClientCheck(extraFilter)
        val body = buildJsonObject {
            // With a local filter Notion returns extra results, so ask for more to end up with 50.
            put("page_size", if (clientCheck) 100 else 50)
            if (extraFilter == null) {
                put("filter", statusFilter)
            } else {
                putJsonObject("filter") {
                    putJsonArray("and") {
                        add(statusFilter)
                        add(if (clientCheck) ClientFilter.toServer(extraFilter) else extraFilter)
                    }
                }
            }
            putJsonArray("sorts") {
                sorts.forEach { sort ->
                    addJsonObject {
                        put("property", sort.property)
                        put("direction", if (sort.ascending) "ascending" else "descending")
                    }
                }
            }
        }
        val res = call("POST", "/data_sources/$dataSourceId/query", body)
        val all = res["results"] as? JsonArray ?: return emptyList()
        val results = if (!clientCheck || extraFilter == null) all else JsonArray(
            all.filter { page -> page.obj()?.get("properties").obj()?.let { ClientFilter.matches(extraFilter, it) } ?: false }
                .take(50)
        )
        val relationTitles = relationTitles(results, extraProperties, titleCache)
        return results.mapNotNull {
            parseTask(it.obj(), dateProperty, statusProperty, extraProperties, relationTitles)
        }
    }

    /**
     * The API only returns relation IDs, so we fetch the title of each related page
     * (once per ID, stored in [cache]). If one fails (e.g. the integration has no access
     * to that database) it simply isn't shown.
     */
    private suspend fun relationTitles(
        results: JsonArray,
        extraProperties: List<String>,
        cache: MutableMap<String, String?>,
    ): Map<String, String> {
        val ids = results.flatMap { page ->
            val props = page.obj()?.get("properties").obj()
            extraProperties.flatMap { relationIds(props?.get(it).obj()) }
        }.toSet()
        return ids.mapNotNull { id ->
            val title = if (id in cache) cache[id] else {
                runCatching { call("GET", "/pages/$id")["properties"].obj()?.let(::titleOf) }
                    .getOrNull().also { cache[id] = it }
            }
            title?.let { id to it }
        }.toMap()
    }

    private fun relationIds(prop: JsonObject?): List<String> =
        if (prop?.get("type").str() != "relation") emptyList()
        else (prop?.get("relation") as? JsonArray)?.mapNotNull { it.obj()?.get("id").str() }.orEmpty()

    private fun titleOf(props: JsonObject): String? {
        val titleProp = props.values.map { it.obj() }.firstOrNull { it?.get("type").str() == "title" }
        return (titleProp?.get("title") as? JsonArray)
            ?.joinToString("") { it.obj()?.get("plain_text").str().orEmpty() }
            ?.ifBlank { null }
    }

    /** All data source properties, sorted alphabetically; options keep Notion's order. */
    suspend fun schema(dataSourceId: String): List<PropertyInfo> {
        val props = call("GET", "/data_sources/$dataSourceId")["properties"].obj() ?: return emptyList()
        return props.mapNotNull { (name, value) ->
            val prop = value.obj() ?: return@mapNotNull null
            val type = prop["type"].str() ?: return@mapNotNull null
            val config = prop[type].obj()
            val options = (config?.get("options") as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
            val completeIds = (config?.get("groups") as? JsonArray)
                ?.firstOrNull { it.obj()?.get("name").str() == "Complete" }.obj()?.get("option_ids") as? JsonArray
            val complete = completeIds?.firstOrNull().str()
                ?.let { id -> options.firstOrNull { it["id"].str() == id }?.get("name").str() }
            PropertyInfo(name, type, options.mapNotNull { it["name"].str() }, complete)
        }.sortedBy { it.name.lowercase() }
    }

    /** Select properties with their options, in Notion's order. */
    suspend fun selectOptions(dataSourceId: String): List<Pair<String, List<String>>> =
        schema(dataSourceId).filter { it.type == "select" }.map { it.name to it.options }

    /**
     * Creates a task in the data source and returns its URL. [date] is "yyyy-MM-dd" or null;
     * [selects] maps property → option for select properties.
     */
    suspend fun createTask(
        dataSourceId: String,
        title: String,
        dateProperty: String,
        date: String?,
        selects: Map<String, String> = emptyMap(),
    ): String? {
        val titleProperty = schema(dataSourceId).firstOrNull { it.type == "title" }?.name
            ?: throw NotionException(0, "The database has no title property")
        val body = buildJsonObject {
            putJsonObject("parent") {
                put("type", "data_source_id")
                put("data_source_id", dataSourceId)
            }
            putJsonObject("properties") {
                putJsonObject(titleProperty) {
                    putJsonArray("title") {
                        addJsonObject { putJsonObject("text") { put("content", title) } }
                    }
                }
                if (date != null) putJsonObject(dateProperty) { putJsonObject("date") { put("start", date) } }
                selects.forEach { (property, option) ->
                    putJsonObject(property) { putJsonObject("select") { put("name", option) } }
                }
            }
        }
        return call("POST", "/pages", body)["url"].str()
    }

    /** Sets the page's status to [doneValue]. */
    suspend fun markDone(pageId: String, statusProperty: String, statusType: String, doneValue: String) {
        val body = buildJsonObject {
            putJsonObject("properties") {
                putJsonObject(statusProperty) {
                    putJsonObject(statusType) { put("name", doneValue) }
                }
            }
        }
        call("PATCH", "/pages/$pageId", body)
    }

    private fun parseTask(
        page: JsonObject?,
        dateProperty: String,
        statusProperty: String,
        extraProperties: List<String>,
        relationTitles: Map<String, String>,
    ): Task? {
        page ?: return null
        val id = page["id"].str() ?: return null
        val props = page["properties"].obj() ?: return null

        val title = titleOf(props) ?: "Untitled"

        val dateObj = props[dateProperty].obj()?.get("date").obj()
        val date = dateObj?.get("start").str()
        val endDate = dateObj?.get("end").str()

        val statusObj = props[statusProperty].obj()
        val statusType = statusObj?.get("type").str() ?: "status"
        val status = statusObj?.get(statusType).obj()?.get("name").str()

        return Task(
            id = id,
            title = title,
            url = page["url"].str() ?: "https://www.notion.so/${id.replace("-", "")}",
            date = date,
            endDate = endDate,
            status = status,
            statusType = statusType,
            extras = extraProperties.map { propertyText(props[it].obj(), relationTitles) },
            extraColors = extraProperties.map { propertyColor(props[it].obj()) },
        )
    }

    /** Option color for select/status (or the first one for multi-select). */
    private fun propertyColor(prop: JsonObject?): String? {
        prop ?: return null
        val type = prop["type"].str() ?: return null
        return when (type) {
            "select", "status" -> prop[type].obj()?.get("color").str()
            "multi_select" -> (prop[type] as? JsonArray)?.firstOrNull().obj()?.get("color").str()
            else -> null
        }
    }

    /** A property value as short text; "" if empty or the type isn't supported. */
    private fun propertyText(prop: JsonObject?, relationTitles: Map<String, String>): String {
        prop ?: return ""
        val type = prop["type"].str() ?: return ""
        val value = prop[type]
        return when (type) {
            "select", "status" -> value.obj()?.get("name").str().orEmpty()
            "multi_select" -> (value as? JsonArray)
                ?.mapNotNull { it.obj()?.get("name").str() }?.joinToString(", ").orEmpty()
            "rich_text", "title" -> (value as? JsonArray)
                ?.joinToString("") { it.obj()?.get("plain_text").str().orEmpty() }.orEmpty()
            "number" -> (value as? JsonPrimitive)?.takeIf { !it.isString && it.content != "null" }?.content.orEmpty()
            "url", "email", "phone_number" -> value.str().orEmpty()
            "relation" -> relationIds(prop).mapNotNull { relationTitles[it] }.joinToString(", ")
            else -> ""
        }
    }

    private suspend fun call(method: String, path: String, body: JsonObject? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.notion.com/v1$path")
                .header("Authorization", "Bearer $token")
                .header("Notion-Version", NOTION_VERSION)
                .method(method, body?.toString()?.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = runCatching {
                        AppJson.parseToJsonElement(text).jsonObject["message"].str()
                    }.getOrNull()
                    throw NotionException(response.code, msg ?: "HTTP ${response.code}")
                }
                AppJson.parseToJsonElement(text).jsonObject
            }
        }

    companion object {
        const val NOTION_VERSION = "2025-09-03"
        /** Types propertyText() understands. */
        val DISPLAYABLE_TYPES = setOf(
            "select", "status", "multi_select", "rich_text", "number", "url", "email", "phone_number", "relation",
        )
        /** Types Notion allows sorting a query by. */
        val SORTABLE_TYPES = setOf(
            "title", "date", "select", "status", "number", "checkbox", "rich_text", "formula",
            "url", "email", "phone_number", "created_time", "last_edited_time", "unique_id",
        )
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

private val DATE_PLACEHOLDER = Regex("""\{today([+-]\d+)?\}""")

/** Replaces {today}, {today+N} and {today-N} with the local date (yyyy-MM-dd) shifted by N days. */
private fun expandDates(json: String): String = DATE_PLACEHOLDER.replace(json) { m ->
    val offset = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
    LocalDate.now().plusDays(offset).toString()
}

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
