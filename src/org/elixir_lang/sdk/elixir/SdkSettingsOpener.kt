package org.elixir_lang.sdk.elixir

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager

/** Which settings a notice sends the user to; IntelliJ IDEA has one Project Structure for both. */
enum class SettingsPage { MODULE_SDKS, SDKS }

interface SdkSettingsOpener {
    fun open(event: AnActionEvent, page: SettingsPage = SettingsPage.SDKS)

    fun targetName(): String

    companion object {
        fun getInstance(): SdkSettingsOpener =
            ApplicationManager.getApplication().getService(SdkSettingsOpener::class.java)
    }
}

internal class SettingsSdkSettingsOpener : SdkSettingsOpener {
    override fun open(event: AnActionEvent, page: SettingsPage) {
        val project = event.project ?: ProjectManager.getInstance().openProjects.firstOrNull()
        val id = when (page) {
            SettingsPage.MODULE_SDKS -> "language.elixir"
            SettingsPage.SDKS -> "language.elixir.sdks.elixir"
        }
        // Finding a page by class builds every page ahead of it, and some, such as the IDE's data-sharing consents,
        // do slow work when built; a page's `id` from plugin.xml is known without building it.
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { it is SearchableConfigurable && it.id == id },
            null
        )
    }

    override fun targetName(): String = "Settings"
}

internal class ProjectStructureSdkSettingsOpener : SdkSettingsOpener {
    override fun open(event: AnActionEvent, page: SettingsPage) {
        val action = ActionManager.getInstance().getAction("ShowProjectStructureSettings")
        if (action != null) {
            ActionUtil.performAction(action, event)
        }
    }

    override fun targetName(): String = "Project Structure"
}
