package com.aarijokhan.diffgraph.actions

import com.aarijokhan.diffgraph.analysis.BlastRadiusCalculator
import com.aarijokhan.diffgraph.analysis.ChangeParser
import com.aarijokhan.diffgraph.analysis.DependencyGraphBuilder
import com.aarijokhan.diffgraph.analysis.GitDiffProvider
import com.aarijokhan.diffgraph.model.BlastRadiusEntry
import com.aarijokhan.diffgraph.model.ChangedFile
import com.aarijokhan.diffgraph.model.ChangedSymbol
import com.aarijokhan.diffgraph.model.DependencyGraph
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
                Messages.showInfoMessage(project, dump, "DiffGraph: Phase 2 Dump")
            }
        }
    }

    private fun buildDump(project: Project): String {
        val files = GitDiffProvider().getStagedChanges(project)
        if (files.isEmpty()) return "No staged changes. Stage files with `git add` first."

        val symbols = ChangeParser().parseSymbols(project, files)
        val changedPaths = files.map { it.projectRelativePath }.toSet()
        val graph = DependencyGraphBuilder().build(project, symbols, changedPaths)
        val blast = BlastRadiusCalculator().calculate(project, symbols, changedPaths)

        val sb = StringBuilder()
        appendFilesAndSymbols(sb, files, symbols)
        sb.append("\nDEPENDENCY GRAPH\n")
        appendGraph(sb, graph)
        sb.append("\nBLAST RADIUS (top 5)\n")
        appendBlast(sb, blast, 5)
        sb.append("\nFiles: ${files.size}   Symbols: ${symbols.size}   Edges: ${graph.edges.size}")
        return sb.toString()
    }

    private fun appendFilesAndSymbols(sb: StringBuilder, files: List<ChangedFile>, symbols: List<ChangedSymbol>) {
        val byFile = symbols.groupBy { it.filePath }
        for (file in files) {
            sb.append(formatFile(file))
            val fileSymbols = byFile[file.projectRelativePath].orEmpty()
            if (fileSymbols.isEmpty() && file.hasPsiSupport) {
                sb.append("  (no symbols detected)\n")
            }
            for (s in fileSymbols) sb.append(formatSymbol(s))
        }
    }

    private fun appendGraph(sb: StringBuilder, graph: DependencyGraph) {
        if (graph.nodes.isEmpty()) {
            sb.append("  (no symbols to graph)\n")
            return
        }
        val roots = graph.roots.joinToString(", ") { "${it.filePath}::${it.name}" }
        sb.append("  Roots: ${roots.ifBlank { "(none)" }}\n")
        if (graph.edges.isEmpty()) {
            sb.append("  Edges: (none)\n")
        } else {
            sb.append("  Edges:\n")
            for (e in graph.edges) {
                sb.append("    ${e.upstream.name} → ${e.downstream.filePath}::${e.downstream.name}\n")
            }
        }
        val order = graph.topologicalOrder.joinToString(", ") { it.name }
        sb.append("  Topological order: $order\n")
    }

    private fun appendBlast(sb: StringBuilder, entries: List<BlastRadiusEntry>, limit: Int) {
        if (entries.isEmpty()) {
            sb.append("  (no symbols)\n")
            return
        }
        for (entry in entries.take(limit)) {
            sb.append(
                "  ${entry.symbol.name}  total=${entry.totalUsages}  changed=${entry.usagesInChangedFiles}  unchanged=${entry.usagesInUnchangedFiles}\n"
            )
        }
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
