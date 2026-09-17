package org.elixir_lang.ui.framework.core

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.runToolWindow
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.components.elements.balloon
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import org.elixir_lang.notification.setup_sdk.Notifier.MIX_DEPS_OUTDATED_TITLE
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * The part of an Elixir import that is the same whichever IDE is running it.
 *
 * Where the IDEs differ is SDK configuration - Project Structure in a rich IDE, Settings in a small one - so that
 * step stays in each product's own test and everything after it lives here.
 */

/**
 * The title the notification actually renders.
 *
 * `Notifier` builds it as "<title> (<Mix root name>)", and the driver's `balloon(text)` matches the visible text
 * exactly - so the bare constant matches nothing, however long it is waited for.
 */
private val mixDepsOutdatedTitle: String
    get() = "$MIX_DEPS_OUTDATED_TITLE (${Path.of(IdeTestContext.projectPath).fileName})"

/** Installs deps from the notification the deps watcher raises when `deps/` is missing or stale. */
fun Driver.installMixDeps() = step("Install Mix deps") {
    ideFrame {
        val notificationBalloon = balloon(mixDepsOutdatedTitle)
        // Notification actions appear as ActionLink components in the balloon
        val installButton = notificationBalloon.x { byVisibleText("Install deps") }
        installButton.click()
        waitUntilReady()
    }
}

/** Runs the project's ExUnit tests from the project view and waits for the run window to report them passing. */
fun Driver.runExUnitTests() = step("Run ExUnit tests") {
    ideFrame {
        projectView {
            projectViewTree.rightClickPath(".", "test", fullMatch = false)
        }
        popupMenu().selectContains("Run 'Mix ExUnit")

        // Wait for test runner to open and tests to execute
        waitUntilReady(5.minutes)

        runToolWindow {
            // The quoter's own test count. Asserted exactly rather than as "tests passed", so that a change to
            // the fixture project surfaces here instead of passing quietly against a different number.
            waitContainsText("4 tests passed", null, true, 5.minutes)
        }
    }
}

/** The deps notification must go once deps are installed, or the watcher is reporting a state that has passed. */
fun Driver.assertDepsNotificationGone() = step("Verify deps watcher isn't showing anymore") {
    ideFrame {
        waitUntilReady()
        val notificationBalloon = balloon(mixDepsOutdatedTitle)
        assertTrue(notificationBalloon.notPresent())
    }
}
