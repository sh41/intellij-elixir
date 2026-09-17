package org.elixir_lang.ui.framework.core

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeActionByShortcut
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.common.FileChooserDialogUi
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.WindowUiComponent
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.UiRobot
import com.intellij.driver.sdk.ui.components.elements.actionButton
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.fileChooser
import com.intellij.driver.sdk.ui.components.elements.jBlist
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import java.awt.event.KeyEvent
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Extensions for project and SDK operations.
 */

/**
 * Cleans IntelliJ IDEA project configuration files and Mix build artifacts to force a fresh import.
 *
 * This removes:
 * - .idea directory (contains project settings, workspace, etc.)
 * - quoter.iml (module file for the quoter project the tests open)
 * - deps directory (Mix dependencies)
 * - _build directory (Mix build artifacts)
 *
 * This ensures OpenProcessor.openProjectAsync() and Builder.commit() are called,
 * which properly sets up directory marks (source folders, exclude folders).
 *
 * @param projectPath The absolute file system path to the project directory
 */
private fun cleanIdeaFiles(projectPath: String) {
    step("Clean existing IntelliJ configuration and Mix build files") {
        val projectDir = java.io.File(projectPath)
        val pathsToClean = listOf(".idea", "quoter.iml", "deps", "_build", "out")

        pathsToClean.forEach { path ->
            val file = java.io.File(projectDir, path)
            if (file.exists()) {
                println("Deleting $path at: ${file.absolutePath}")
                if (file.isDirectory) file.deleteRecursively() else file.delete()
                println("$path deleted successfully")
            } else {
                println("No existing $path found")
            }
        }
    }
}

/**
 * Opens an existing project at the specified path.
 *
 * This method uses the welcome screen to open a project by entering the path
 * in the file chooser dialog.
 *
 * IMPORTANT: Cleans any existing .idea folder and .iml files to force the OpenProcessor flow
 * and ensure proper project import with directory marks.
 *
 * @receiver Driver The UI test driver instance
 * @param path The absolute file system path to the project directory
 * @throws Exception if the project cannot be opened or UI components are not found
 */
fun Driver.openProjectAt(path: String) = step("Open project at $path") {
    // Clean any existing IntelliJ configuration to force OpenProcessor flow
    // Without this, IntelliJ opens the existing project config and bypasses
    // OpenProcessor.openProjectAsync() and Builder.commit(), which means
    // directory marks (source folders, exclude folders) are never set up
    cleanIdeaFiles(path)

    invokeAction("OpenFile", now = false)
    ui.chooseDirectory("Open File or Project", path)

    ideFrame {
        waitUntilReady()
    }
}

/**
 * Types [path] into a file chooser and accepts it.
 *
 * Not the SDK's `FileChooserDialogUi.openPath`, which assigns the path field's text and then waits three seconds -
 * not extendable - for the tree to catch up. Typing makes the chooser navigate on its own.
 */
fun UiRobot.chooseDirectory(dialogTitle: String, path: String) =
    fileChooser({ byTitle(dialogTitle) }) { selectDirectory(path) }

/** @see chooseDirectory */
fun WindowUiComponent.chooseDirectory(dialogTitle: String, path: String) =
    fileChooser({ byTitle(dialogTitle) }) { selectDirectory(path) }

/**
 * Accepts [path] in a file chooser, typing it only when the chooser is not already showing it.
 *
 * Normalised before anything else: a `..` segment is typed into the completion helper like any other, and no
 * directory is ever listed under that name, so the helper stays shut. The resolved Elixir SDK home ends in `bin/..`.
 */
private fun FileChooserDialogUi.selectDirectory(path: String) {
    val wanted = Path.of(path).normalize().toString()

    pathTextField.waitUntilReady()
    // The chooser usually opens already showing the directory wanted. Typing is the slowest and most fragile part
    // of a run, so it is skipped when the field already names it.
    if (!pathTextField.text.namesSameDirectoryAs(wanted)) {
        typeDirectory(wanted)
    }

    okButton.click()
}

/** Separator- and case-insensitive: the chooser rewrites what it is given, and Windows paths are case-insensitive. */
private fun String.namesSameDirectoryAs(wanted: String): Boolean =
    trim().replace('\\', '/').trimEnd('/')
        .equals(wanted.replace('\\', '/').trimEnd('/'), ignoreCase = true)

/**
 * Driven the way the chooser expects: a separator opens its completion helper, the segment filters the list, and
 * the exactly-matching entry is clicked.
 *
 * The separator is sent as a key code, not as text. `typeText` goes through `robot.type(char)`, which emits
 * nothing at all for a backslash - the rest of the path then lands in the helper's list instead of the field, and
 * the chooser is left on whatever prefix did arrive.
 */
private fun FileChooserDialogUi.typeDirectory(wanted: String) {
    val segments = wanted.replace('\\', '/').split('/').filter(String::isNotEmpty)

    // Hidden directories are filtered out of the completion list as well as the file list, and a tool manager
    // installs its SDKs under one - `AppData` on Windows. The setting outlives the chooser it was changed in, so
    // this has to be conditional: clicking in every chooser would turn hidden files back off in the second one.
    val showHiddenFiles = actionButton { byAccessibleName("Show Hidden Files") }
    if (!showHiddenFiles.isSelected) showHiddenFiles.click()

    // The chooser pre-fills the last path it was given, and typing appends to it - the result matches nothing and
    // no completion list ever opens. Assigned rather than typed: clearing drives no listener, only the typing does.
    pathTextField.text = ""
    pathTextField.setFocus()
    Thread.sleep(300)

    pathTextField.keyboard { typeText(segments.first()) }

    segments.drop(1).forEach { segment ->
        pathTextField.keyboard {
            key(KeyEvent.VK_BACK_SLASH)
            typeText(segment)
        }
        // The entry is clicked rather than accepted with Enter, which takes whichever entry the helper has
        // highlighted - its matching is fuzzy, so "AppData" highlights "Application Data" and the rest of the
        // path is then built under the wrong directory.
        // Scoped to the popup's own window. The completion list is not inside the chooser dialog, so it has to
        // be searched for globally - but a bare JBList match is ambiguous wherever the screen holds more than
        // one, as Project Structure does. Matching the list by a child carrying the item text does not work: a
        // JBList paints its items through a cell renderer rather than holding them as components.
        driver.ui.popup().jBlist().clickItem(segment, fullMatch = true)
    }
}

/**
 * Adds both SDKs and assigns the module's SDK, in a single visit to Settings.
 *
 * How a small IDE reaches SDKs at all: RubyMine and the others have no Project Structure dialog, so
 * [addSdkFromDisk] cannot work there. The plugin draws the same line through `SdkSettingsOpener`, which is
 * `ProjectStructureSdkSettingsOpener` on a rich platform and `SettingsSdkSettingsOpener` everywhere else. The
 * Elixir page is a project configurable, so a project must already be open.
 *
 * One visit rather than three, and that is the point: the Elixir page sees the Erlang SDK added moments before,
 * and the module chooser sees the Elixir one, without the dialog being committed in between. The pages share a
 * single `ProjectSdksModel` and follow it through listeners, so a round-trip through OK is not needed - and
 * closing Settings between steps would hide whether any of that works.
 *
 * Unlike Project Structure's "Add New SDK", no SDK-type popup appears: each page already knows its type, so
 * `ProjectSdksModel.doAdd` goes straight to the home chooser.
 *
 * @param erlangSdkPath home of the Erlang SDK, added first because the Elixir SDK depends on one
 * @param elixirSdkPath home of the Elixir SDK
 */
fun Driver.configureElixirViaSettings(erlangSdkPath: String, elixirSdkPath: String) =
    step("Configure Elixir SDKs and the module SDK in one visit to Settings") {
        ideFrame { openSettingsDialog() }

        addSdkOnSettingsPage(
            "Internal Erlang SDKs",
            erlangSdkPath,
            "Select Home Directory for Erlang SDK for Elixir SDK",
        )
        addSdkOnSettingsPage("SDKs", elixirSdkPath, "Select Home Directory for Elixir SDK")

        ideFrame {
            settingsDialog {
                // The parent page, which carries the per-module SDK chooser.
                openTreeSettingsSection("Languages & Frameworks", "Elixir")
                // Only Elixir SDKs are offered here, so the substring cannot match the Erlang entry - whose name
                // does contain "Elixir" ("mise Erlang for Elixir 29.0.6").
                comboBox().selectItemContains("Elixir")
                // The status line under the chooser, which renders `ModuleSdkStatus`. An SDK just added through
                // this page must classify as usable: only the ready form names the paired Erlang SDK, while one
                // the page cannot classify renders "- Invalid SDK". Waited for rather than read, because the
                // label is computed off the EDT and posted back.
                waitContainsText("Erlang:", null, true, 1.minutes)
                clickButtonText("OK")
            }
            waitUntilReady()
        }
    }

/**
 * Adds one SDK from the page named [settingsPage], leaving Settings open.
 *
 * The whole tree path from the root: "Elixir" declares `parentId="language"`, so it renders under
 * "Languages & Frameworks".
 */
private fun Driver.addSdkOnSettingsPage(settingsPage: String, sdkPath: String, dialogTitle: String) =
    step("Add SDK on the $settingsPage page from $sdkPath") {
        ideFrame {
            settingsDialog {
                openTreeSettingsSection("Languages & Frameworks", "Elixir", settingsPage)
                clickButtonText("Add")
            }
        }

        ui.chooseDirectory(dialogTitle, sdkPath)
    }

/**
 * Adds a new SDK from disk using the Project Structure settings dialog.
 *
 * This method opens Project Structure, navigates to the SDKs section, and adds
 * a new SDK by selecting its type and specifying the installation path.
 *
 * @receiver WindowUiComponent The window component (typically welcomeScreen)
 * @param sdkType The type of SDK to add (e.g., "Add Erlang SDK", "Add Elixir SDK")
 * @param sdkPath The absolute file system path to the SDK installation directory
 * @param dialogTitle The expected title of the SDK home directory selection dialog
 * @throws Exception if the SDK cannot be added or UI components are not found
 */
fun WindowUiComponent.addSdkFromDisk(sdkType: String, sdkPath: String, dialogTitle: String) =
    step("Add SDK: $sdkType from $sdkPath") {
        invokeActionByShortcut("ShowProjectStructureSettings")
        projectStructure {
            waitUntilReady()
            driver.selectItemByAccessibleName("Project structure categories", "SDKs")
            clickButtonText("Add New SDK")
        }

        driver.selectItemFromList(sdkType)

        chooseDirectory(dialogTitle, sdkPath)

        projectStructure {
            clickButtonText("OK")
        }
        waitUntilReady()
    }
