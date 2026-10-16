package com.galaxytv.app

import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private var selectedSection = Section.ADVANCED

    private enum class Section(
        val title: String,
        val description: String,
        val labels: List<String>,
        val values: List<String>
    ) {
        ACCOUNT(
            "Account",
            "Your Galaxy TV activation details",
            listOf("Activation code", "Expires"),
            emptyList()
        ),
        ADVANCED(
            "Advanced",
            "Application and device information",
            listOf("Android version", "Device", "MAC address"),
            emptyList()
        ),
        APPEARANCE(
            "Appearance",
            "Current display preferences",
            listOf("Theme", "Accent"),
            listOf("Dark", "Galaxy blue")
        ),
        ABOUT(
            "About",
            "About Galaxy TV",
            listOf("Application", "Version"),
            listOf("Galaxy TV", "")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val buttons = mapOf(
            R.id.accountSettingsTab to Section.ACCOUNT,
            R.id.advancedSettingsTab to Section.ADVANCED,
            R.id.appearanceSettingsTab to Section.APPEARANCE,
            R.id.aboutSettingsTab to Section.ABOUT
        )
        buttons.forEach { (buttonId, section) ->
            findViewById<Button>(buttonId).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                backgroundTintList = null
                setOnFocusChangeListener { focusedView, hasFocus ->
                    focusedView.scaleX = if (hasFocus) 1.03f else 1f
                    focusedView.scaleY = if (hasFocus) 1.03f else 1f
                    focusedView.elevation = if (hasFocus) 8f else 0f
                    updateTabBackground(this, section == selectedSection)
                }
                setOnClickListener { showSection(section, versionName) }
            }
        }
        findViewById<Button>(R.id.settingsLogoutButton).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            backgroundTintList = null
            setOnFocusChangeListener { focusedView, hasFocus ->
                focusedView.scaleX = if (hasFocus) 1.03f else 1f
                focusedView.scaleY = if (hasFocus) 1.03f else 1f
                focusedView.elevation = if (hasFocus) 8f else 0f
                setBackgroundResource(
                    if (hasFocus) R.drawable.settings_logout_focused
                    else R.drawable.settings_logout
                )
                backgroundTintList = null
            }
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Log out")
                    .setMessage("Do you want to sign out of Galaxy TV?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Log out") { _, _ ->
                        ActivationSession.clear(this@SettingsActivity)
                        finish()
                    }
                    .show()
            }
        }

        showSection(Section.ADVANCED, versionName)
        findViewById<Button>(R.id.advancedSettingsTab).post {
            findViewById<Button>(R.id.advancedSettingsTab).requestFocus()
        }
    }

    private fun showSection(section: Section, versionName: String) {
        selectedSection = section
        findViewById<TextView>(R.id.settingsSectionTitle).text = section.title
        findViewById<TextView>(R.id.settingsSectionDescription).text = section.description

        val labels = listOf(
            findViewById<TextView>(R.id.settingsRowOneLabel),
            findViewById<TextView>(R.id.settingsRowTwoLabel),
            findViewById<TextView>(R.id.settingsRowThreeLabel)
        )
        val values = listOf(
            findViewById<TextView>(R.id.settingsRowOneValue),
            findViewById<TextView>(R.id.settingsRowTwoValue),
            findViewById<TextView>(R.id.settingsRowThreeValue)
        )
        labels.forEachIndexed { index, label ->
            val visible = index < section.labels.size
            label.text = section.labels.getOrElse(index) { "" }
            label.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            values[index].visibility = label.visibility
        }

        val sectionValues = when (section) {
            Section.ACCOUNT -> listOf(
                ActivationSession.activeCode(this).orEmpty().ifBlank { "Not available" },
                expirationLabel()
            )
            Section.ADVANCED -> listOf(
                Build.VERSION.RELEASE,
                Build.MODEL,
                DeviceMacAddress.read() ?: getString(R.string.device_mac_unavailable)
            )
            Section.ABOUT -> listOf("Galaxy TV", versionName)
            else -> section.values
        }
        values.forEachIndexed { index, value ->
            value.text = sectionValues.getOrElse(index) { "" }
        }

        val tabs = listOf(
            R.id.accountSettingsTab to Section.ACCOUNT,
            R.id.advancedSettingsTab to Section.ADVANCED,
            R.id.appearanceSettingsTab to Section.APPEARANCE,
            R.id.aboutSettingsTab to Section.ABOUT
        )
        tabs.forEach { (tabId, tabSection) ->
            updateTabBackground(findViewById(tabId), tabSection == section)
        }
    }

    private fun updateTabBackground(button: Button, isSelected: Boolean) {
        button.isActivated = isSelected
        button.setBackgroundResource(
            when {
                button.hasFocus() -> R.drawable.settings_menu_focused
                isSelected -> R.drawable.settings_menu_selected
                else -> R.drawable.settings_menu_item
            }
        )
        button.backgroundTintList = null
    }

    private fun expirationLabel(): String {
        val expiresAt = ActivationSession.expirationTime(this) ?: return "Not available"
        if (expiresAt == Long.MAX_VALUE) return "No expiration"
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expiresAt))
    }
}
