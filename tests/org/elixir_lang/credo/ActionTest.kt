package org.elixir_lang.credo

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.settings.SettingsPageLookup

class ActionTest : BasePlatformTestCase() {
    fun testConfigureCredoOpensTheCredoPage() {
        SettingsPageLookup(testRootDisposable).assertOpens(Configurable::class.java) {
            Action(project).actionPerformed(
                TestActionEvent.createTestEvent(SimpleDataContext.getProjectContext(project)),
                Notification("Credo", "", NotificationType.WARNING)
            )
        }
    }
}
