package com.notionwidget.data

import android.content.Context
import com.notionwidget.widget.TasksWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.LocalDate

/**
 * Single source of truth: queries Notion, keeps a disk cache
 * and pushes the result to every widget.
 */
class TaskRepository(private val context: Context) {

    private val cacheFile get() = File(context.filesDir, "tasks.json")
    private val prefs get() = context.getSharedPreferences("notion", Context.MODE_PRIVATE)
    private val api get() = NotionApi(config.token)

    /** Connection settings entered in the app. */
    val config: NotionConfig
        get() = prefs.getString(CONFIG_KEY, null)
            ?.let { runCatching { AppJson.decodeFromString<NotionConfig>(it) }.getOrNull() }
            ?: NotionConfig()

    fun saveConfig(config: NotionConfig) {
        prefs.edit().putString(CONFIG_KEY, AppJson.encodeToString(NotionConfig.serializer(), config)).apply()
    }

    val isConfigured: Boolean get() = config.isComplete

    suspend fun loadRaw(): String? = withContext(Dispatchers.IO) {
        cacheFile.takeIf { it.exists() }?.readText()
    }

    suspend fun load(): WidgetData =
        loadRaw()?.let { runCatching { AppJson.decodeFromString<WidgetData>(it) }.getOrNull() } ?: WidgetData()

    /** Downloads pending tasks. Returns false on failure (the error is shown in the widget). */
    suspend fun refresh(): Boolean = lock.withLock {
        val config = config
        if (!config.isComplete) {
            save(load().copy(error = "Open the app to connect your Notion database"))
            return false
        }
        try {
            val dataSourceId = dataSourceId()
            // Shared across views so the same related page isn't fetched twice.
            val titleCache = mutableMapOf<String, String?>()
            val views = viewFilters(config.dateProperty).map { (name, filter) ->
                val settings = viewSettings(name)
                TaskView(
                    name = name,
                    tasks = api.queryPending(
                        dataSourceId = dataSourceId,
                        dateProperty = config.dateProperty,
                        statusProperty = config.statusProperty,
                        statusType = config.statusType,
                        doneValue = config.doneValue,
                        extraFilterJson = filter.toString(),
                        extraProperties = settings.properties.orEmpty(),
                        titleCache = titleCache,
                        sorts = settings.sorts ?: defaultSorts,
                    ),
                )
            }
            save(WidgetData(views = views, updatedAt = System.currentTimeMillis(), error = null))
            true
        } catch (e: Exception) {
            save(load().copy(error = friendlyError(e)))
            false
        }
    }

    /** Marks a task as done. Removes it from the widget immediately and reverts on failure. */
    suspend fun complete(pageId: String) {
        val before = lock.withLock {
            val current = load()
            // A task can be in several views: remove it from all of them.
            val views = current.views.map { v -> v.copy(tasks = v.tasks.filterNot { it.id == pageId }) }
            save(current.copy(views = views, error = null))
            current
        }
        val task = before.views.flatMap { it.tasks }.firstOrNull { it.id == pageId }
        try {
            api.markDone(
                pageId = pageId,
                statusProperty = config.statusProperty,
                statusType = task?.statusType ?: config.statusType,
                doneValue = config.doneValue,
            )
        } catch (e: Exception) {
            lock.withLock { save(before.copy(error = "Couldn't complete the task: ${friendlyError(e)}")) }
        }
    }

    /**
     * Creates a task in Notion and refreshes the widgets. Returns the new page's URL
     * (may be null) or the user-facing error on failure.
     */
    suspend fun createTask(
        title: String,
        date: LocalDate?,
        selects: Map<String, String>,
    ): Result<String?> =
        try {
            val url = api.createTask(dataSourceId(), title, config.dateProperty, date?.toString(), selects)
            RefreshWorker.refreshNow(context)
            Result.success(url)
        } catch (e: Exception) {
            Result.failure(Exception(friendlyError(e)))
        }

    /** Select properties and their options, for the new-task form. */
    suspend fun selectOptions(): Result<List<Pair<String, List<String>>>> =
        try {
            Result.success(api.selectOptions(dataSourceId()))
        } catch (e: Exception) {
            Result.failure(Exception(friendlyError(e)))
        }

    /** Names of the views (tabs). */
    val viewNames: List<String> get() = VIEW_NAMES

    /** Default sort: by date, oldest first. */
    val defaultSorts: List<SortSpec> get() = listOf(SortSpec(config.dateProperty))

    /** Settings for view [name]. */
    fun viewSettings(name: String): ViewSettings =
        prefs.getString(viewKey(name), null)
            ?.let { runCatching { AppJson.decodeFromString<ViewSettings>(it) }.getOrNull() }
            ?: ViewSettings()

    fun saveViewSettings(name: String, settings: ViewSettings) {
        prefs.edit().putString(viewKey(name), AppJson.encodeToString(ViewSettings.serializer(), settings)).apply()
    }

    private fun viewKey(name: String) = "view_settings_$name"

    /** The database's properties, or the error message if they couldn't be loaded. */
    suspend fun schema(): Result<List<PropertyInfo>> =
        try {
            Result.success(api.schema(dataSourceId()))
        } catch (e: Exception) {
            Result.failure(Exception(friendlyError(e)))
        }

    private suspend fun dataSourceId(): String {
        val dbId = config.databaseId
        prefs.getString("ds_for_$dbId", null)?.let { return it }
        return api.firstDataSourceId(dbId).also {
            prefs.edit().putString("ds_for_$dbId", it).apply()
        }
    }

    private suspend fun save(data: WidgetData) {
        val raw = AppJson.encodeToString(WidgetData.serializer(), data)
        withContext(Dispatchers.IO) { cacheFile.writeText(raw) }
        TasksWidget.pushToAll(context, raw)
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is NotionException -> when (e.code) {
            401 -> "Invalid token"
            404 -> "Database not found (did you connect it to the integration?)"
            400 -> "Notion: ${e.message}"
            429 -> "Too many requests, will retry later"
            else -> e.message ?: "Notion error"
        }
        is java.io.IOException -> "No connection"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private val lock = Mutex()
        private const val CONFIG_KEY = "config"
        private val VIEW_NAMES = listOf("Today", "Up next")
        /** Maximum that fits in a widget row (Glance limits a Row's children). */
        const val MAX_EXTRA_PROPERTIES = 4

        /**
         * Filter of each view, AND-ed with "status is not done". {today+N} is replaced on each refresh.
         * Today: due today or overdue (a range counts until its end). Up next: tomorrow to 7 days ahead.
         */
        private fun viewFilters(dateProperty: String): List<Pair<String, JsonObject>> {
            fun date(key: String, op: String, value: String) = buildJsonObject {
                put("property", dateProperty)
                putJsonObject(key) { put(op, value) }
            }
            return VIEW_NAMES.zip(listOf(
                date(ClientFilter.DATE_END, "before", "{today+1}"),
                buildJsonObject {
                    putJsonArray("and") {
                        add(date("date", "on_or_after", "{today+1}"))
                        add(date("date", "on_or_before", "{today+7}"))
                    }
                },
            ))
        }

        /**
         * Extracts the database ID from a Notion link or a bare ID (with or without dashes).
         * In a link it's the 32 characters at the end of the path, before "?v=" (which is a view ID).
         */
        fun parseDatabaseId(input: String): String? {
            val path = input.trim().substringBefore('?').substringBefore('#').trimEnd('/')
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .find(path)?.let { return it.value.replace("-", "").lowercase() }
            return Regex("[0-9a-fA-F]{32}$").find(path)?.value?.lowercase()
        }
    }
}
