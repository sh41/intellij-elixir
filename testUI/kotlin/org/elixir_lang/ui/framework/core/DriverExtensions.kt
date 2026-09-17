package org.elixir_lang.ui.framework.core

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.JComboBoxUiComponent
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.actionButton
import com.intellij.driver.sdk.ui.components.elements.jBlist
import com.intellij.driver.sdk.ui.components.idea.ProjectStructureUI
import com.intellij.driver.sdk.ui.remote.Window
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.util.system.OS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

var DEFAULT_TIMEOUT = 30.seconds
var DEFAULT_MINIMUM_WAIT = 0.seconds

/**
 * Reusable UI helper extensions for common UI test operations.
 */

/**
 * Enters [text] into a combo box, through the combo box's own editor.
 *
 * A combo box does not own focus itself - its internal editor does - and that editor needs a moment after
 * `setFocus()` before it accepts input; without the wait the first characters are lost.
 *
 * @param editorInitDelayMs Milliseconds to wait for editor initialization (default: 300ms)
 */
fun JComboBoxUiComponent.enterTextWithFocus(text: String, editorInitDelayMs: Long = 300) {
    waitUntilReady()
    setFocus()
    Thread.sleep(editorInitDelayMs)
    enterText(text)
}

/**
 * Focuses any component and types [text] as real key events.
 *
 * For a plain text field, which has no editor fixture to enter text through. Assigning `JTextFieldUI.text` instead
 * calls `setText` on the EDT, which does not drive the listeners a file chooser's path field uses to navigate its
 * tree - and `FileChooserDialogImpl.doOKAction` takes its result from that tree, so OK would accept whatever was
 * selected before. Typing drives them.
 *
 * @param editorInitDelayMs Milliseconds to wait after focusing before typing (default: 300ms)
 */
fun UiComponent.enterTextWithFocus(text: String, editorInitDelayMs: Long = 300) {
    waitUntilReady()
    setFocus()
    Thread.sleep(editorInitDelayMs)
    keyboard { typeText(text) }
}

/**
 * Clicks a button in a dialog using accessibleName or visible text.
 *
 * @receiver UiComponent The dialog component containing the button
 * @param buttonNameOrVisibleText The accessible name or visible text of the button
 * @throws Exception if the button cannot be found or clicked
 */
fun UiComponent.clickButtonText(buttonNameOrVisibleText: String) {
    actionButton(
        {
            or(
                byVisibleText(buttonNameOrVisibleText),
                byAccessibleName(buttonNameOrVisibleText)
            )
        }
    ).click()
}

/**
 * Selects an item from a JList component.
 *
 * This method searches for an item in a JList and clicks it. If no list is provided,
 * it searches for a list with class "MyList" in the current UI context.
 *
 * @receiver Driver The UI test driver instance
 * @param item The text of the item to select (partial match)
 * @param list Optional specific JList component to use; if null, searches for "MyList"
 * @throws Exception if the list or item cannot be found
 */
fun Driver.selectItemFromList(item: String, list: JListUiComponent? = null) {
    val myList = list ?: ui.jBlist(xQuery {
        byClass("MyList")
    })
    myList.waitUntilReady()
    myList.clickItem(item, fullMatch = false)
}

/**
 * Selects an item from a JList component identified by its accessible name.
 *
 * This is useful when you need to select from a specific list that has an accessible name,
 * such as "Project structure categories" in the Project Structure dialog.
 *
 * @receiver Driver The UI test driver instance
 * @param accessibleName The accessible name of the JList to find
 * @param item The text of the item to select
 * @param fullMatch Whether to match the item text exactly (default: false for partial match)
 * @throws Exception if the list or item cannot be found
 */
fun Driver.selectItemByAccessibleName(
    accessibleName: String,
    item: String,
    fullMatch: Boolean = false
) {
    ui.jBlist(xQuery { byAccessibleName(accessibleName) }).clickItem(item, fullMatch)
}

/**
 * Waits for a UI component to be ready for interaction.
 *
 * Use this INSIDE component DSL blocks (projectStructure, dialog, etc.)
 * to ensure the component is present and ready before interacting with it.
 *
 * @receiver UiComponent The UI component to wait for
 * @param timeout Maximum duration to wait (default: 5 minutes)
 * @param minimum Minimum duration to wait before checking (default: 0 seconds)
 * @throws Exception if the component is not present within the timeout period
 */
fun UiComponent.waitUntilReady(timeout: Duration = DEFAULT_TIMEOUT, minimum: Duration = DEFAULT_MINIMUM_WAIT) {
    step("Wait for UiComponent $this to be ready for $timeout") {
        Thread.sleep(minimum.inWholeMilliseconds)
        this.should(message = "Component $this should be present", timeout = timeout) { present() }
    }
}

/**
 * Waits for an IdeaFrameUI to be ready for interaction.
 *
 * This specialized version waits for all background indicators to complete,
 * which is necessary for the main IDE frame to be fully initialized.
 *
 * @receiver IdeaFrameUI The IDE frame to wait for
 * @param timeout Maximum duration to wait (default: 5 minutes)
 * @param minimum Minimum duration to wait before checking (default: 0 seconds)
 * @throws Exception if indicators don't complete within the timeout period
 */
fun IdeaFrameUI.waitUntilReady(timeout: Duration = DEFAULT_TIMEOUT, minimum: Duration = DEFAULT_MINIMUM_WAIT) {
    step("Wait for IdeaFrameUI $this to be ready for $timeout") {
        Thread.sleep(minimum.inWholeMilliseconds)
        driver.waitForIndicators(timeout)
    }
}

/**
 * Custom helper for Project Structure dialog that allows partial title matching.
 *
 * This handles cases where the title might be "Project Structure", "Default Project Structure", etc.
 *
 * Unlike the SDK's projectStructure which only matches exact "Project Structure" title,
 * this version uses contains() with the title attribute to match any dialog with
 * "Project Structure" in the title.
 *
 * @receiver Finder The finder context
 * @param code The code block to execute on the Project Structure UI
 * @return ProjectStructureUI The project structure dialog component
 */
fun Finder.projectStructure(code: ProjectStructureUI.() -> Unit = {}): ProjectStructureUI =
    x(ProjectStructureUI::class.java) {
        contains(byTitle("Project Structure"))
    }.apply(code)

@Remote("com.intellij.ui.WinFocusStealer")
private interface WinFocusStealer {
    fun setFocusStealingEnabled(value: Boolean)
}

@Remote("com.intellij.ui.AppIcon")
private interface AppIcon {
    fun getInstance(): AppIcon
    fun requestFocus(window: Window?)
}

/**
 * Ensures the IDE window is foregrounded so input events are delivered reliably.
 */
fun Driver.ensureIdeInForeground() = step("Bring IDE window to foreground") {
    if (OS.CURRENT == OS.Windows) {
        // Allow the IDE to request focus even when the app is inactive on Windows.
        withContext {
            utility(WinFocusStealer::class).setFocusStealingEnabled(true)
        }
    }

    val appIcon = withContext { utility(AppIcon::class).getInstance() }
    runCatching {
        ideFrame {
            if (isMinimized()) {
                unminimize()
            }
            val window = driver.cast(component, Window::class)
            appIcon.requestFocus(window)
            window.requestFocus()
            toFront()
            requestFocusFromIde(project)
        }
    }
}

fun Driver.ensureWelcomeScreenInForeground() = step("Bring Welcome Screen to foreground") {
    if (OS.CURRENT == OS.Windows) {
        // Allow the IDE to request focus even when the app is inactive on Windows.
        withContext {
            utility(WinFocusStealer::class).setFocusStealingEnabled(true)
        }
    }
    val appIcon = withContext { utility(AppIcon::class).getInstance() }
    runCatching {
        welcomeScreen {
            val window = driver.cast(component, Window::class)
            appIcon.requestFocus(window)
            window.requestFocus()
            toFront()
        }
    }
}
