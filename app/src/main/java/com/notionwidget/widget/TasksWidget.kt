package com.notionwidget.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.notionwidget.AddTaskActivity
import com.notionwidget.data.AppJson
import com.notionwidget.data.Task
import com.notionwidget.data.TaskRepository
import com.notionwidget.data.WidgetData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TasksWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A newly added widget starts with whatever is in the cache.
        val prefs = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)
        if (prefs[DataKey] == null) {
            TaskRepository(context).loadRaw()?.let { raw ->
                updateAppWidgetState(context, id) { it[DataKey] = raw }
            }
        }
        provideContent {
            val state = currentState<Preferences>()
            val data = state[DataKey]?.let { runCatching { AppJson.decodeFromString<WidgetData>(it) }.getOrNull() }
            val selected = (state[ViewKey] ?: 0).coerceIn(0, ((data?.views?.size ?: 1) - 1).coerceAtLeast(0))
            GlanceTheme { Content(data, selected) }
        }
    }

    companion object {
        val DataKey = stringPreferencesKey("widget_data")
        /** Selected view; each widget instance has its own. */
        val ViewKey = intPreferencesKey("view_index")

        suspend fun pushToAll(context: Context, raw: String) {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(TasksWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { it[DataKey] = raw }
                TasksWidget().update(context, id)
            }
        }
    }
}

private val Red = ColorProvider(Color(0xFFD93025))
private val Orange = ColorProvider(Color(0xFFE37400))
private val Transparent = ColorProvider(Color.Transparent)

@Composable
private fun Content(data: WidgetData?, selected: Int) {
    val view = data?.views?.getOrNull(selected)
    val tasks = view?.tasks.orEmpty()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Header(data, selected)
        data?.error?.let {
            Text(
                text = it,
                maxLines = 2,
                style = TextStyle(color = GlanceTheme.colors.error, fontSize = 12.sp),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        when {
            data == null -> Message("Loading…")
            tasks.isEmpty() && data.error == null -> Message("Nothing pending 🎉")
            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                items(tasks, itemId = { it.id.hashCode().toLong() }) { task -> TaskRow(task) }
            }
        }
    }
}

private fun countSuffix(n: Int) = if (n > 0) " · $n" else ""

@Composable
private fun Header(data: WidgetData?, selected: Int) {
    val views = data?.views.orEmpty()
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (views.size > 1) {
            // Tabs: the selected one is highlighted; tapping another switches view.
            Row(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                views.forEachIndexed { i, view ->
                    val isSelected = i == selected
                    Text(
                        text = view.name + countSuffix(view.tasks.size),
                        maxLines = 1,
                        style = TextStyle(
                            color = if (isSelected) GlanceTheme.colors.onSecondaryContainer
                            else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        modifier = GlanceModifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .cornerRadius(12.dp)
                            // Always set a background (transparent if not selected): widget views are
                            // reused and otherwise the previous tab stayed highlighted.
                            .background(if (isSelected) GlanceTheme.colors.secondaryContainer else Transparent)
                            .clickable(
                                actionRunCallback<SelectViewAction>(actionParametersOf(SelectViewAction.IndexKey to i))
                            ),
                    )
                    if (i < views.lastIndex) Spacer(GlanceModifier.width(4.dp))
                }
            }
        } else {
            Text(
                text = (views.firstOrNull()?.name?.ifBlank { null } ?: "Tasks") +
                    countSuffix(views.firstOrNull()?.tasks?.size ?: 0),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        if (data != null && data.updatedAt > 0) {
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(data.updatedAt)),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
        Text(
            text = "+",
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier
                .padding(start = 10.dp)
                .clickable(actionStartActivity<AddTaskActivity>()),
        )
        Text(
            text = "↻",
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier
                .padding(start = 10.dp, end = 2.dp)
                .clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

@Composable
private fun TaskRow(task: Task) {
    val label = dateLabel(task.date, task.endDate)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<CompleteTaskAction>(
                actionParametersOf(CompleteTaskAction.PageIdKey to task.id)
            ),
        )
        Spacer(GlanceModifier.width(4.dp))
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(task.url)))),
        ) {
            Text(
                text = task.title,
                maxLines = 2,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            )
            // Glance limits a Row to 10 children, so at most 4 extras.
            val extras = task.extras.mapIndexedNotNull { i, text ->
                text.takeIf { it.isNotBlank() }?.let { it to tagColors(task.extraColors.getOrNull(i)) }
            }.take(TaskRepository.MAX_EXTRA_PROPERTIES)
            if (label != null || extras.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (label != null) {
                        Text(
                            text = label.text,
                            style = TextStyle(
                                color = when {
                                    label.overdue -> Red
                                    label.today -> Orange
                                    else -> GlanceTheme.colors.onSurfaceVariant
                                },
                                fontSize = 12.sp,
                                fontWeight = if (label.overdue || label.today) FontWeight.Medium else FontWeight.Normal,
                            ),
                        )
                    }
                    extras.forEachIndexed { i, (text, colors) ->
                        if (label != null || i > 0) Spacer(GlanceModifier.width(6.dp))
                        if (colors != null) {
                            // Tag with the option's Notion colors.
                            Text(
                                text = text,
                                maxLines = 1,
                                style = TextStyle(color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                modifier = GlanceModifier
                                    .background(colors.background)
                                    .cornerRadius(4.dp)
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        } else {
                            Text(
                                text = text,
                                maxLines = 1,
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                                // Same reason as the tabs: don't inherit a tag's background.
                                modifier = GlanceModifier.background(Transparent),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
    }
}

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val pageId = parameters[PageIdKey] ?: return
        TaskRepository(context).complete(pageId)
    }

    companion object {
        val PageIdKey = ActionParameters.Key<String>("pageId")
    }
}

class SelectViewAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[IndexKey] ?: return
        updateAppWidgetState(context, glanceId) { it[TasksWidget.ViewKey] = index }
        TasksWidget().update(context, glanceId)
    }

    companion object {
        val IndexKey = ActionParameters.Key<Int>("viewIndex")
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TaskRepository(context).refresh()
    }
}
