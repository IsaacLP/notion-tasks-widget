package com.notionwidget.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Conditions the Notion API doesn't support, evaluated by the app itself.
 *
 * The only one so far is `date_end`: like `date` but on the END of the range
 * (or the start if it isn't a range), like the "End date" in Notion's filters. E.g.
 * `{"property":"Due","date_end":{"before":"{today}"}}` = overdue.
 *
 * How it works: Notion receives a broader version of the filter ([toServer]) and then
 * [matches] evaluates the full filter on each result. Local evaluation only happens if
 * the filter uses `date_end`; unrecognized conditions are treated as matching
 * (Notion already applied them, except inside an "or" with `date_end`).
 */
object ClientFilter {

    const val DATE_END = "date_end"

    fun needsClientCheck(filter: JsonElement?): Boolean = when (filter) {
        is JsonObject -> DATE_END in filter || filter.values.any { needsClientCheck(it) }
        is JsonArray -> filter.any { needsClientCheck(it) }
        else -> false
    }

    /** Replaces each `date_end` with a condition on the start that covers all of its matches. */
    fun toServer(filter: JsonObject): JsonObject {
        filter["and"]?.let { return JsonObject(mapOf("and" to JsonArray((it as JsonArray).map { f -> toServer(f as JsonObject) }))) }
        filter["or"]?.let { return JsonObject(mapOf("or" to JsonArray((it as JsonArray).map { f -> toServer(f as JsonObject) }))) }
        val cond = filter[DATE_END] as? JsonObject ?: return filter
        val property = filter["property"]
        val (op, value) = cond.entries.firstOrNull() ?: return filter
        return buildJsonObject {
            put("property", property!!)
            // end >= start: "end before X" implies "start before X". For the rest, just require a date.
            put("date", if (op == "before" || op == "on_or_before") JsonObject(mapOf(op to value))
            else buildJsonObject { put("is_not_empty", true) })
        }
    }

    /** Evaluates [filter] against a page's properties. */
    fun matches(filter: JsonObject, props: JsonObject): Boolean {
        (filter["and"] as? JsonArray)?.let { list -> return list.all { matches(it as JsonObject, props) } }
        (filter["or"] as? JsonArray)?.let { list -> return list.any { matches(it as JsonObject, props) } }
        val name = (filter["property"] as? JsonPrimitive)?.content ?: return true
        val prop = props[name] as? JsonObject ?: return true
        val (type, cond) = filter.entries.firstOrNull { it.key != "property" } ?: return true
        val (op, value) = (cond as? JsonObject)?.entries?.firstOrNull() ?: return true
        return when (type) {
            "date", DATE_END -> {
                val date = prop["date"] as? JsonObject
                val raw = (if (type == DATE_END) date?.get("end").str() ?: date?.get("start").str() else date?.get("start").str())
                dateMatches(raw?.let(::localDay), op, value)
            }
            "checkbox" -> {
                val checked = (prop["checkbox"] as? JsonPrimitive)?.booleanOrNull ?: false
                val target = (value as? JsonPrimitive)?.booleanOrNull ?: return true
                when (op) { "equals" -> checked == target; "does_not_equal" -> checked != target; else -> true }
            }
            "select", "status" -> {
                val selected = (prop[prop["type"].str() ?: type] as? JsonObject)?.get("name").str()
                when (op) {
                    "equals" -> selected == value.str()
                    "does_not_equal" -> selected != value.str()
                    "is_empty" -> selected == null
                    "is_not_empty" -> selected != null
                    else -> true
                }
            }
            "multi_select" -> {
                val names = (prop["multi_select"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("name").str() }.orEmpty()
                when (op) {
                    "contains" -> value.str() in names
                    "does_not_contain" -> value.str() !in names
                    "is_empty" -> names.isEmpty()
                    "is_not_empty" -> names.isNotEmpty()
                    else -> true
                }
            }
            else -> true
        }
    }

    private fun dateMatches(day: LocalDate?, op: String, value: JsonElement): Boolean {
        val today = LocalDate.now()
        return when (op) {
            "is_empty" -> day == null
            "is_not_empty" -> day != null
            "next_week" -> day != null && !day.isBefore(today) && !day.isAfter(today.plusDays(7))
            "past_week" -> day != null && !day.isAfter(today) && !day.isBefore(today.minusDays(7))
            else -> {
                day ?: return false
                val target = value.str()?.let { runCatching { localDay(it) }.getOrNull() } ?: return true
                when (op) {
                    "equals" -> day == target
                    "before" -> day.isBefore(target)
                    "after" -> day.isAfter(target)
                    "on_or_before" -> !day.isAfter(target)
                    "on_or_after" -> !day.isBefore(target)
                    else -> true
                }
            }
        }
    }

    /** Local day (device time zone) of a Notion date, with or without time. */
    fun localDay(raw: String): LocalDate =
        if (raw.length > 10) OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
        else LocalDate.parse(raw)

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
