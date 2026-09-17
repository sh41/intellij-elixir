package org.elixir_lang.ui.rubymine

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.components.elements.ActionButtonUi
import com.intellij.driver.sdk.ui.components.elements.fileChooser
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import org.elixir_lang.ui.framework.core.*
import org.junit.jupiter.api.Test

/**
 * Prints what the file chooser's toolbar buttons actually expose, so a locator can be written against the real
 * attributes rather than an action's bundle text - which is not what the component carries.
 *
 * Not part of the suite's coverage: a probe.
 */
class RubyMineChooserProbeTest {

    @Test
    fun printsTheFileChooserToolbarButtons() {
        step("List the file chooser's action buttons") {
            val ctx = IdeTestContext.setupTestContext("RubyMineChooserProbeTest", IdeProductProvider.RM)

            ctx.runIdeWithDriver().useDriverAndCloseIde {
                ensureWelcomeScreenInForeground()

                invokeAction("OpenFile", now = false)
                ui.fileChooser({ byTitle("Open File or Project") }) {
                    pathTextField.waitUntilReady()

                    xx("//div[@class='ActionButton']", ActionButtonUi::class.java).list().forEach { button ->
                        println("CHOOSER-BUTTON text='${button.text}' icon='${button.icon}'")
                    }
                }
            }
        }
    }
}
