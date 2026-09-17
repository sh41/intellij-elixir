package org.elixir_lang.ui.rubymine

import com.intellij.driver.sdk.step
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import org.elixir_lang.ui.framework.core.*
import org.junit.jupiter.api.Test

/**
 * Importing an Elixir project in RubyMine, which has no Project Structure dialog.
 *
 * SDKs are configured through Settings instead, and its Elixir page is a project configurable, so a project has to
 * be open before they can be added - the opposite order to IDEA. The plugin draws the same line through
 * `SdkSettingsOpener`, whose small-IDE implementation opens Settings on the Elixir SDKs page.
 */
class RubyMineProjectImportTest {

    @Test
    fun importsElixirProjectAndInstallsDeps() {
        step("Run test: import an Elixir project in RubyMine") {
            val ctx = IdeTestContext.setupTestContext("RubyMineProjectImportTest", IdeProductProvider.RM)

            ctx.runIdeWithDriver().useDriverAndCloseIde {
                ensureWelcomeScreenInForeground()

                openProjectAt(IdeTestContext.projectPath)
                ensureIdeInForeground()

                // One visit to Settings for both SDKs and the module assignment. That the Elixir page sees the
                // Erlang SDK added moments earlier - and the module chooser sees the Elixir one - without the
                // dialog being committed in between is the shared SDK model doing its job.
                //
                // Erlang first: the Elixir SDK is a DependentSdkType, and with exactly one Erlang SDK present its
                // custom create UI picks that one silently instead of prompting.
                configureElixirViaSettings(IdeTestContext.erlangSdkPath, IdeTestContext.elixirSdkPath)

                installMixDeps()
                runExUnitTests()
                assertDepsNotificationGone()
            }
        }
    }
}
