package com.notionwidget

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.notionwidget.data.TaskRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small dialog-themed window for creating a task from the widget's "+" button. */
class AddTaskActivity : Activity() {

    private val scope = MainScope()
    private lateinit var repo: TaskRepository
    private var date: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = TaskRepository(this)
        val pad = dp(20)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8))
        }
        layout.addView(TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            text = "New task"
        })
        val title = EditText(this).apply {
            hint = "Title"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        layout.addView(title)

        // --- Date: shortcuts plus a picker for any other day.
        layout.addView(label(repo.config.dateProperty))
        // RadioGroup only unchecks buttons that have an id; without one "No date" stayed checked.
        val other = RadioButton(this).apply { id = View.generateViewId(); text = "Other…" }
        val dates = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf<Pair<String, () -> LocalDate?>>(
                "No date" to { null },
                "Today" to { LocalDate.now() },
                "Tomorrow" to { LocalDate.now().plusDays(1) },
            ).forEachIndexed { i, (text, pick) ->
                val button = RadioButton(context).apply {
                    id = View.generateViewId()
                    this.text = text
                    setOnClickListener { date = pick(); other.text = "Other…" }
                }
                addView(button)
                if (i == 0) check(button.id)
            }
            addView(other)
        }
        var previous = dates.checkedRadioButtonId
        dates.setOnCheckedChangeListener { _, id -> if (id != other.id) previous = id }
        other.setOnClickListener {
            val initial = date ?: LocalDate.now()
            var picked = false
            DatePickerDialog(this, { _, y, m, d ->
                picked = true
                date = LocalDate.of(y, m + 1, d)
                other.text = date!!.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                // If dismissed without picking, go back to the previous option (the date hasn't changed).
                setOnDismissListener { if (!picked && other.text.toString() == "Other…") dates.check(previous) }
            }.show()
        }
        layout.addView(dates)

        // --- Select properties (e.g. Tag, Priority): loaded from Notion on open.
        val selectsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(selectsBox)
        val selects = mutableMapOf<String, Spinner>()
        scope.launch {
            repo.selectOptions().onSuccess { props ->
                props.filter { it.second.isNotEmpty() }.forEach { (name, options) ->
                    selectsBox.addView(label(name))
                    val spinner = Spinner(this@AddTaskActivity).apply {
                        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf(NONE) + options)
                    }
                    selectsBox.addView(spinner)
                    selects[name] = spinner
                }
            }
            // Offline the task can still be created, just without these properties.
        }

        val error = TextView(this).apply { setTextColor(0xFFD93025.toInt()); textSize = 13f }
        layout.addView(error)

        val add = Button(this).apply { text = "Add" }
        val addAndOpen = Button(this).apply { text = "Add & open" }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            // Same width for all three so they also fit on narrow screens.
            val share = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            addView(Button(context).apply {
                text = "Cancel"
                setOnClickListener { finish() }
            }, share())
            addView(addAndOpen, share())
            addView(add, share())
        }
        layout.addView(buttons)

        /** Creates the task; with [open], opens it in Notion afterwards to edit the rest. */
        fun submit(open: Boolean) {
            val text = title.text.toString().trim()
            if (text.isEmpty()) {
                title.error = "Enter a title"
                return
            }
            add.isEnabled = false
            addAndOpen.isEnabled = false
            error.text = ""
            val chosen = selects.mapNotNull { (name, spinner) ->
                (spinner.selectedItem as? String)?.takeIf { spinner.selectedItemPosition > 0 }?.let { name to it }
            }.toMap()
            scope.launch {
                repo.createTask(text, date, chosen)
                    .onSuccess { url ->
                        Toast.makeText(this@AddTaskActivity, "Task added", Toast.LENGTH_SHORT).show()
                        if (open && url != null) {
                            // Opens the Notion app if installed; otherwise the browser.
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                        finish()
                    }
                    .onFailure {
                        error.text = "Couldn't add the task: ${it.message}"
                        add.isEnabled = true
                        addAndOpen.isEnabled = true
                    }
            }
        }
        add.setOnClickListener { submit(open = false) }
        addAndOpen.setOnClickListener { submit(open = true) }
        title.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) submit(open = false) }
        }

        setContentView(layout)
        title.requestFocus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun label(text: String) = TextView(this).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, 0)
        this.text = text
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val NONE = "—"
    }
}
