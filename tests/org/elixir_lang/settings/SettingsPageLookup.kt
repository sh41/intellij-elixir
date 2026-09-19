package org.elixir_lang.settings

import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.ConfigurableWrapper
import com.intellij.openapi.project.Project
import com.intellij.testFramework.replaceService
import junit.framework.TestCase.assertNotNull

/**
 * Runs the platform's real Settings page lookup and records the page it would open instead of showing a dialog.
 *
 * Pages are looked up by the `id` string plugin.xml gives them, which the compiler cannot check.
 */
class SettingsPageLookup(disposable: Disposable) {
    private val shown = RecordingShowSettingsUtil()

    init {
        ApplicationManager.getApplication().replaceService(ShowSettingsUtil::class.java, shown, disposable)
    }

    fun assertOpens(expectedClass: Class<out Configurable>, open: () -> Unit) {
        open()

        assertNotNull("expected ${expectedClass.name} to open", ConfigurableWrapper.cast(expectedClass, shown.selected))
    }

    private class RecordingShowSettingsUtil : ShowSettingsUtilImpl() {
        var selected: Configurable? = null

        override fun doShow(project: Project?, groups: List<ConfigurableGroup>, toSelect: Configurable?, filter: String?) {
            selected = toSelect
        }
    }
}
