package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.ChangedSymbol
import com.aarijokhan.diffgraph.model.UsageLocation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch

internal class SymbolUsageIndex(
    private val project: Project,
    val symbols: List<ChangedSymbol>,
    val changedFilePaths: Set<String>
) {

    private val resolved: Map<ChangedSymbol, PsiNamedElement>
    private val rangesByFile: Map<String, List<Pair<TextRange, ChangedSymbol>>>
    private val referencesBySymbol: Map<ChangedSymbol, List<PsiReference>>

    init {
        val (resolvedMap, rangesMap) = ApplicationManager.getApplication()
            .runReadAction<Pair<Map<ChangedSymbol, PsiNamedElement>, Map<String, List<Pair<TextRange, ChangedSymbol>>>>> {
                val resMap = LinkedHashMap<ChangedSymbol, PsiNamedElement>()
                val rangeMap = LinkedHashMap<String, MutableList<Pair<TextRange, ChangedSymbol>>>()
                for (symbol in symbols) {
                    val el = resolveSymbol(symbol) ?: continue
                    resMap[symbol] = el
                    val range = el.textRange ?: continue
                    rangeMap.getOrPut(symbol.filePath) { mutableListOf() }.add(range to symbol)
                }
                resMap to rangeMap
            }
        resolved = resolvedMap
        rangesByFile = rangesMap
        referencesBySymbol = ApplicationManager.getApplication()
            .runReadAction<Map<ChangedSymbol, List<PsiReference>>> {
                resolved.mapValues { (_, el) ->
                    try {
                        ReferencesSearch.search(el).findAll().toList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
    }

    fun referencesOf(symbol: ChangedSymbol): List<PsiReference> = referencesBySymbol[symbol].orEmpty()

    fun enclosingChangedSymbolOf(reference: PsiReference): ChangedSymbol? {
        val element = reference.element
        val vFile = element.containingFile?.virtualFile ?: return null
        val filePath = projectRelativePath(vFile) ?: return null
        if (filePath !in changedFilePaths) return null
        val candidates = rangesByFile[filePath] ?: return null
        val refRange = element.textRange ?: return null
        return candidates
            .filter { (range, _) -> range.contains(refRange) }
            .minByOrNull { (range, _) -> range.length }
            ?.second
    }

    fun usageLocationOf(reference: PsiReference): UsageLocation? {
        val element = reference.element
        val psiFile = element.containingFile ?: return null
        val vFile = psiFile.virtualFile ?: return null
        val filePath = projectRelativePath(vFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val startOffset = element.textRange?.startOffset ?: return null
        val line = document.getLineNumber(startOffset) + 1
        return UsageLocation(
            filePath = filePath,
            line = line,
            isInChangedFile = filePath in changedFilePaths
        )
    }

    fun projectRelativePath(vFile: VirtualFile): String? {
        val base = project.basePath
        if (base != null && vFile.path.startsWith(base)) {
            val trimmed = vFile.path.removePrefix(base).trimStart('/')
            if (trimmed.isNotEmpty()) return trimmed
        }
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
        for (root in contentRoots) {
            val rel = VfsUtilCore.getRelativePath(vFile, root)
            if (rel != null) return rel
        }
        return null
    }

    private fun resolveVirtualFile(projectRelativePath: String): VirtualFile? {
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath("$base/$projectRelativePath")?.let { return it }
        }
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
        for (root in contentRoots) {
            root.findFileByRelativePath(projectRelativePath)?.let { return it }
        }
        return null
    }

    private fun resolveSymbol(symbol: ChangedSymbol): PsiNamedElement? {
        val vFile = resolveVirtualFile(symbol.filePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val line = symbol.startLineInNewFile
        if (line < 1 || line > document.lineCount) return null
        val lineStart = document.getLineStartOffset(line - 1)
        val lineEnd = document.getLineEndOffset(line - 1)

        var offset = lineStart
        while (offset < lineEnd) {
            val el = psiFile.findElementAt(offset)
            if (el != null) {
                val match = walkUpForName(el, symbol.name)
                if (match != null) return match
            }
            offset++
        }
        return null
    }

    private fun walkUpForName(start: PsiElement, name: String): PsiNamedElement? {
        var current: PsiElement? = start
        while (current != null && current !is PsiFile) {
            if (current is PsiNamedElement && current.name == name) return current
            current = current.parent
        }
        return null
    }
}
