package com.notionwidget.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Task(
    val id: String,
    val title: String,
    val url: String,
    /** "2026-10-14" or "2026-10-14T10:00:00.000+02:00", exactly as Notion returns it. */
    val date: String? = null,
    /** End of the range, if the date is a range (same format as [date]). */
    val endDate: String? = null,
    val status: String? = null,
    /** "status" or "select": used when marking it as done. */
    val statusType: String = "status",
    /** Values (as text) of the view's extra properties, in the same order. */
    val extras: List<String> = emptyList(),
    /** Notion color ("red", "blue"…) of each value in [extras]; null if none. */
    val extraColors: List<String?> = emptyList(),
)

@Serializable
data class SortSpec(
    val property: String,
    val ascending: Boolean = true,
)

/** Notion connection, set up in the app. */
@Serializable
data class NotionConfig(
    val token: String = "",
    /** 32 hex characters, without dashes. */
    val databaseId: String = "",
    val dateProperty: String = "",
    val statusProperty: String = "",
    /** "status" or "select": the type of [statusProperty]. */
    val statusType: String = "status",
    /** Option of [statusProperty] that means the task is done. */
    val doneValue: String = "",
) {
    val isComplete: Boolean
        get() = listOf(token, databaseId, dateProperty, statusProperty, doneValue).all { it.isNotBlank() }
}

/** Per-view settings chosen in the app; null = use the default. */
@Serializable
data class ViewSettings(
    val properties: List<String>? = null,
    val sorts: List<SortSpec>? = null,
)

/** A widget "view": tab name and its tasks. */
@Serializable
data class TaskView(
    val name: String = "",
    val tasks: List<Task> = emptyList(),
)

@Serializable
data class WidgetData(
    val views: List<TaskView> = emptyList(),
    val updatedAt: Long = 0L,
    val error: String? = null,
)

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
