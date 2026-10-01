package org.elixir_lang.debugger

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.impl.getModuleName
import org.elixir_lang.util.ReadActions

/** The modules a breakpoint at [offset] stops in, or `null` when [file] is not an Elixir file. */
internal fun breakpointModuleNames(project: Project, file: VirtualFile, offset: Int): Set<String>? =
    ReadActions.compute {
        (PsiManager.getInstance(project).findFile(file) as? ElixirFile)?.let { elixirFile ->
            // TODO allow multiple module names for `defimpl`
            elixirFile.findElementAt(offset)?.getModuleName()?.let { setOf(it) } ?: emptySet()
        }
    }
