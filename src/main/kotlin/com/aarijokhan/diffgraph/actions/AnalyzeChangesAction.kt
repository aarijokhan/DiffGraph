package com.aarijokhan.diffgraph.actions

import com.aarijokhan.diffgraph.analysis.ChangeParser
import com.aarijokhan.diffgraph.analysis.GitDiffProvider
import com.aarijokhan.diffgraph.model.ChangedFile
import com.aarijokhan.diffgraph.model.ChangedSymbol
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class AnalyzeChangesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val dump = runCatching { buildDump(project) }.getOrElse { t -> "Error: ${t.message}" }
            ApplicationManager.getApplication().invokeLater {
                Messages.showInfoMessage(project, dump, "DiffGraph: Phase 1 Dump")
            }
        }
    }

    private fun buildDump(project: Project): String {
        val provider = GitDiffProvider()
        val parser = ChangeParser()
        val files = provider.getStagedChanges(project)
        if (files.isEmpty()) return "No staged changes. Stage files with `git add` first."

        val symbols = parser.parseSymbols(project, files)
        val symbolsByFile = symbols.groupBy { it.filePath }
        val sb = StringBuilder()
        for (file in files) {
            sb.append(formatFile(file))
            val fileSymbols = symbolsByFile[file.projectRelativePath].orEmpty()
            if (fileSymbols.isEmpty() && file.hasPsiSupport) {
                sb.append("  (no symbols detected)\n")
            }
            for (s in fileSymbols) {
                sb.append(formatSymbol(s))
            }
        }
        sb.append("\nFiles: ${files.size}   Symbols: ${symbols.size}")
        return sb.toString()
    }

    private fun formatFile(f: ChangedFile): String {
        val binaryTag = if (f.isBinary) " [binary]" else ""
        val psiTag = if (!f.hasPsiSupport) " [no-psi]" else ""
        val renameSuffix = f.previousProjectRelativePath?.let { " (from $it)" } ?: ""
        return "${f.changeType} ${f.projectRelativePath}$renameSuffix$binaryTag$psiTag\n"
    }

    private fun formatSymbol(s: ChangedSymbol): String {
        val lineRef = if (s.startLineInNewFile >= 0) ", line ${s.startLineInNewFile}" else ""
        return "  ${s.kind} ${s.name} (${s.changeType}$lineRef) — ${s.signatureHint}\n"
    }
}
