package com.notionwidget

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.notionwidget.data.NotionApi
import com.notionwidget.data.PropertyInfo
import com.notionwidget.data.RefreshWorker
import com.notionwidget.data.SortSpec
import com.notionwidget.data.TaskRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings screen: connects to Notion (token + database), picks the date and status properties,
 * adjusts each view (properties and sort) and can force a refresh.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var repo: TaskRepository
    private lateinit var status: TextView
    /** Everything that needs the database schema; rebuilt after connecting. */
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No title bar: on Android 15 (edge-to-edge) the content ended up underneath it.
        // The title is drawn inside the screen instead.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        repo = TaskRepository(this)

        val pad = dp(24)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
            text = getString(R.string.app_name)
        })
        status = TextView(this).apply { textSize = 16f }
        layout.addView(status)
        updateStatus()
        layout.addView(ConnectionSection())
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(content)
        if (repo.config.token.isNotBlank() && repo.config.databaseId.isNotBlank()) loadContent()

        val scroll = ScrollView(this).apply { addView(layout) }
        // Leave room for the status bar, navigation bar and notch.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(scroll)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateStatus() {
        status.text = if (repo.isConfigured) {
            "✓ Connected. To add the widget, long-press the home screen → Widgets → ${getString(R.string.app_name)}."
        } else {
            "Connect your Notion database to get started."
        }
    }

    /** Token and database fields; "Connect" saves them and loads the database's properties. */
    @Suppress("FunctionName")
    private fun ConnectionSection(): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section.addView(header("Notion connection"))
        section.addView(TextView(this).apply {
            textSize = 14f
            text = "1. Create an internal integration at notion.so/my-integrations and copy its secret.\n" +
                "2. In your tasks database: ••• → Connections → add the integration.\n" +
                "3. Paste the secret and the database link (Share → Copy link) below."
        })
        val config = repo.config
        val token = EditText(this).apply {
            hint = "Integration secret (ntn_…)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(config.token)
        }
        val database = EditText(this).apply {
            hint = "Database link or ID"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(config.databaseId)
        }
        section.addView(token)
        section.addView(database)
        section.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener {
                val secret = token.text.toString().trim()
                val id = TaskRepository.parseDatabaseId(database.text.toString())
                if (secret.isEmpty()) token.error = "Paste the integration secret"
                if (id == null) database.error = "No database ID found in this link"
                if (secret.isEmpty() || id == null) return@setOnClickListener
                database.setText(id)
                repo.saveConfig(repo.config.copy(token = secret, databaseId = id))
                updateStatus()
                loadContent(refresh = true)
            }
        })
        return section
    }

    /** Loads the schema and builds the property pickers, the refresh button and one block per view. */
    private fun loadContent(refresh: Boolean = false) {
        content.removeAllViews()
        val message = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(24), 0, 0)
            text = "Loading database…"
        }
        content.addView(message)
        scope.launch {
            repo.schema()
                .onSuccess { props ->
                    content.removeAllViews()
                    if (!PropertiesSection(props)) return@onSuccess
                    content.addView(Button(this@MainActivity).apply {
                        text = "Refresh now"
                        setOnClickListener {
                            refreshWidget()
                            Toast.makeText(context, "Refreshing…", Toast.LENGTH_SHORT).show()
                        }
                    })
                    repo.viewNames.forEach { name -> content.addView(ViewSection(name, props)) }
                    if (refresh && repo.isConfigured) refreshWidget()
                }
                .onFailure { message.text = "Couldn't connect: ${it.message}" }
        }
    }

    /**
     * Date property, status property and its "done" option. Picks sensible defaults and saves
     * them right away, so connecting is enough. Returns false if the database lacks them.
     */
    @Suppress("FunctionName")
    private fun PropertiesSection(props: List<PropertyInfo>): Boolean {
        content.addView(header("Database properties"))
        val dates = props.filter { it.type == "date" }
        val statuses = props.filter { it.type == "status" || it.type == "select" }
        if (dates.isEmpty() || statuses.isEmpty()) {
            content.addView(TextView(this).apply {
                textSize = 14f
                text = "The database needs a date property and a status (or select) property."
            })
            return false
        }
        val config = repo.config
        val statusIndex = statuses.indexOfFirst { it.name == config.statusProperty }.takeIf { it >= 0 }
            ?: statuses.indexOfFirst { it.type == "status" }.coerceAtLeast(0)
        val date = spinnerRow("Date", dates.map { it.name }, dates.indexOfFirst { it.name == config.dateProperty })
        val status = spinnerRow("Status", statuses.map { it.name }, statusIndex)
        val done = spinnerRow("Done when", emptyList(), 0)

        var doneFor: PropertyInfo? = null
        fun fillDone(prop: PropertyInfo) {
            if (prop == doneFor) return
            doneFor = prop
            val saved = repo.config.takeIf { it.statusProperty == prop.name }?.doneValue
            val index = listOfNotNull(saved, prop.completeOption, "Done")
                .map { name -> prop.options.indexOfFirst { it.equals(name, ignoreCase = true) } }
                .firstOrNull { it >= 0 } ?: prop.options.lastIndex
            done.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, prop.options)
            if (index >= 0) done.setSelection(index, false)
        }
        fun save() {
            val statusProp = statuses[status.selectedItemPosition]
            fillDone(statusProp)
            val updated = repo.config.copy(
                dateProperty = dates[date.selectedItemPosition].name,
                statusProperty = statusProp.name,
                statusType = statusProp.type,
                doneValue = done.selectedItem as? String ?: "",
            )
            if (updated != repo.config) {
                repo.saveConfig(updated)
                updateStatus()
                refreshWidget()
            }
        }
        listOf(date, status, done).forEach { spinner -> spinner.onItemSelected { save() } }
        save()
        return true
    }

    /** One block per view with its properties and sort; everything is saved on tap. */
    @Suppress("FunctionName")
    private fun ViewSection(viewName: String, props: List<PropertyInfo>): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, 0)
        }
        section.addView(TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            text = viewName
        })

        // --- Properties: shown in the order they are checked.
        section.addView(subtitle("Properties"))
        val summary = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(4)) }
        section.addView(summary)
        val selected = repo.viewSettings(viewName).properties.orEmpty().toMutableList()
        fun updateSummary() {
            summary.text = if (selected.isEmpty()) "None selected."
            else "Order: " + selected.joinToString(" · ") +
                "\n(in the order you check them, max. ${TaskRepository.MAX_EXTRA_PROPERTIES})"
        }
        updateSummary()
        props.filter { it.type in NotionApi.DISPLAYABLE_TYPES }.forEach { prop ->
            section.addView(CheckBox(this).apply {
                text = "${prop.name}  (${typeLabel(prop.type)})"
                isChecked = prop.name in selected
                setOnCheckedChangeListener { box, checked ->
                    if (checked && selected.size >= TaskRepository.MAX_EXTRA_PROPERTIES) {
                        box.isChecked = false
                        Toast.makeText(
                            context,
                            "At most ${TaskRepository.MAX_EXTRA_PROPERTIES} fit",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnCheckedChangeListener
                    }
                    if (checked) selected += prop.name else selected -= prop.name
                    repo.saveViewSettings(viewName, repo.viewSettings(viewName).copy(properties = selected.toList()))
                    updateSummary()
                    refreshWidget()
                }
            })
        }

        // --- Sort: up to two criteria.
        section.addView(subtitle("Sort"))
        val sortable = props.filter { it.type in NotionApi.SORTABLE_TYPES }.map { it.name }
        val choices = listOf(NONE) + sortable
        val current = repo.viewSettings(viewName).sorts ?: repo.defaultSorts
        val rows = listOf("Sort by", "Then by").mapIndexed { i, label ->
            SortRow(label, choices, current.getOrNull(i)).also { section.addView(it.view) }
        }
        rows.forEach { row ->
            row.onChange = {
                val sorts = rows.mapNotNull { it.value() }
                if (sorts != (repo.viewSettings(viewName).sorts ?: repo.defaultSorts)) {
                    repo.saveViewSettings(viewName, repo.viewSettings(viewName).copy(sorts = sorts))
                    refreshWidget()
                }
            }
        }
        return section
    }

    /** "Sort by [property] [direction]" row. */
    private inner class SortRow(label: String, private val choices: List<String>, initial: SortSpec?) {
        var onChange: () -> Unit = {}
        private val property = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(initial?.property?.let { choices.indexOf(it) }?.takeIf { it >= 0 } ?: 0, false)
        }
        private val direction = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, DIRECTIONS)
            setSelection(if (initial?.ascending == false) 1 else 0, false)
        }
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = label; textSize = 14f; minWidth = dp(96) })
            addView(property, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(direction)
        }

        init {
            // Also fires when the view is set up; onChange only saves if something changed.
            property.onItemSelected { onChange() }
            direction.onItemSelected { onChange() }
        }

        fun value(): SortSpec? = choices[property.selectedItemPosition]
            .takeIf { property.selectedItemPosition > 0 }
            ?.let { SortSpec(it, ascending = direction.selectedItemPosition == 0) }
    }

    /** "[label] [spinner]" row added to [content]. */
    private fun spinnerRow(label: String, items: List<String>, selected: Int): Spinner {
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            if (items.isNotEmpty()) setSelection(selected.coerceIn(0, items.lastIndex), false)
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = label; textSize = 14f; minWidth = dp(96) })
            addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        return spinner
    }

    private fun Spinner.onItemSelected(action: () -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = action()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun header(text: String) = TextView(this).apply {
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(28), 0, dp(4))
        this.text = text
    }

    private fun subtitle(text: String) = TextView(this).apply {
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
        this.text = text
    }

    private fun refreshWidget() {
        RefreshWorker.schedule(this)
        RefreshWorker.refreshNow(this)
    }

    private fun typeLabel(type: String) = when (type) {
        "multi_select" -> "multi-select"
        "rich_text" -> "text"
        "phone_number" -> "phone"
        else -> type
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val NONE = "None"
        val DIRECTIONS = listOf("↑ Asc.", "↓ Desc.")
    }
}
