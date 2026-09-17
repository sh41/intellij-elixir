package org.elixir_lang.ui.idea

import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import org.elixir_lang.ui.framework.core.*
import org.junit.jupiter.api.Test

/**
 * Importing an Elixir project in IntelliJ IDEA, which has Project Structure.
 *
 * SDKs are added there before any project is open, which a small IDE cannot do - see
 * `org.elixir_lang.ui.rubymine.RubyMineProjectImportTest` for that path. The plugin draws the same line through
 * `SdkSettingsOpener`, whose rich-platform implementation performs `ShowProjectStructureSettings`.
 */
class IdeaProjectImportTest {

    @Test
    fun importsElixirProjectAndInstallsDeps() {
        step("Run test: import an Elixir project in IDEA") {
            val ctx = IdeTestContext.setupTestContext("IdeaProjectImportTest", IdeProductProvider.IU)

            ctx.runIdeWithDriver().useDriverAndCloseIde {
                ensureWelcomeScreenInForeground()

                welcomeScreen {
                    addSdkFromDisk(
                        "Add Erlang SDK for Elixir SDK from disk",
                        IdeTestContext.erlangSdkPath,
                        "Select Home Directory for Erlang SDK for Elixir SDK",
                    )

                    addSdkFromDisk(
                        "Add Elixir SDK from disk",
                        IdeTestContext.elixirSdkPath,
                        "Select Home Directory for Elixir SDK",
                    )
                }

                openProjectAt(IdeTestContext.projectPath)
                ensureIdeInForeground()

                installMixDeps()
                runExUnitTests()
                assertDepsNotificationGone()
            }
        }
    }
}
