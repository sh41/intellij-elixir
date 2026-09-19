package org.elixir_lang.sdk.elixir

import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import org.elixir_lang.facet.configurable.SmallIdeTopLevelElixirConfigurableFactory
import org.elixir_lang.facet.configurable.TopLevelElixirConfigurableFactory
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.settings.SettingsPageLookup

class SettingsSdkSettingsOpenerTest : BasePlatformTestCase() {
    private lateinit var lookup: SettingsPageLookup

    override fun setUp() {
        super.setUp()
        lookup = SettingsPageLookup(testRootDisposable)
        // Settings opens these pages only in the small IDEs; the tests run in IntelliJ IDEA.
        ProcessOutput.isSmallIdeOverride = true
        ApplicationManager.getApplication().replaceService(
            TopLevelElixirConfigurableFactory::class.java,
            SmallIdeTopLevelElixirConfigurableFactory(),
            testRootDisposable
        )
    }

    override fun tearDown() {
        try {
            ProcessOutput.isSmallIdeOverride = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testSdksOpensTheElixirSdksPage() {
        assertOpens(SettingsPage.SDKS, org.elixir_lang.facet.sdks.elixir.Configurable::class.java)
    }

    fun testModuleSdksOpensTheElixirPage() {
        assertOpens(SettingsPage.MODULE_SDKS, org.elixir_lang.facet.configurable.Project::class.java)
    }

    private fun assertOpens(page: SettingsPage, expectedClass: Class<out Configurable>) {
        lookup.assertOpens(expectedClass) {
            SettingsSdkSettingsOpener().open(TestActionEvent.createTestEvent(SimpleDataContext.getProjectContext(project)), page)
        }
    }
}
