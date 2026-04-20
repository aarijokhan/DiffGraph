package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.ChangeType
import com.aarijokhan.diffgraph.model.ChangedFile
import com.aarijokhan.diffgraph.model.ChangedSymbol
import com.aarijokhan.diffgraph.model.SymbolKind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement

class ChangeParser {

    fun parseSymbols(project: Project, changedFiles: List<ChangedFile>): List<ChangedSymbol> {
        val result = mutableListOf<ChangedSymbol>()
        for (file in changedFiles) {
            if (!file.hasPsiSupport) continue
            result.addAll(parseFile(project, file))
        }
        return result
    }

    private fun parseFile(project: Project, file: ChangedFile): List<ChangedSymbol> {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(file.absolutePath)

        val psiSymbols = if (virtualFile != null && file.changeType != ChangeType.DELETED) {
            ApplicationManager.getApplication().runReadAction<List<ChangedSymbol>> {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: return@runReadAction emptyList()
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runReadAction emptyList()
                parsePresentSymbols(psiFile, document, file)
            }
        } else {
            emptyList()
        }

        val deletedSymbols = parseDeletedSymbols(file)
        return psiSymbols + deletedSymbols
    }

    internal fun parsePresentSymbols(
        psiFile: PsiFile,
        document: Document,
        file: ChangedFile
    ): List<ChangedSymbol> {
        val addedLines = file.diffHunks.flatMap { it.addedLineNumbers }.toSet()
        val removedLines = file.diffHunks.flatMap { it.removedLineNumbers }.toSet()

        val candidates = linkedSetOf<PsiNamedElement>()
        for (line in addedLines) {
            val named = namedMemberAtLine(psiFile, document, line) ?: continue
            candidates.add(named)
        }

        val minimal = collapseToInnermost(candidates)

        val results = mutableListOf<ChangedSymbol>()
        for (symbol in minimal) {
            val range = symbol.textRange ?: continue
            val startLine = document.getLineNumber(range.startOffset) + 1
            val endLineInclusive = document.getLineNumber((range.endOffset - 1).coerceAtLeast(0)) + 1
            val symbolLines = (startLine..endLineInclusive).toSet()

            val intersectsAdded = symbolLines.any { it in addedLines }
            val intersectsRemoved = symbolLines.any { it in removedLines }
            val allInAdded = symbolLines.isNotEmpty() && symbolLines.all { it in addedLines }

            val changeType = when {
                allInAdded -> ChangeType.ADDED
                intersectsAdded || intersectsRemoved -> ChangeType.MODIFIED
                else -> ChangeType.MODIFIED
            }

            results.add(buildChangedSymbol(symbol, file.projectRelativePath, changeType, startLine))
        }
        return results
    }

    internal fun parseDeletedSymbols(file: ChangedFile): List<ChangedSymbol> {
        val pureRemoveHunks = file.diffHunks.filter {
            it.addedLineNumbers.isEmpty() && it.removedLineNumbers.isNotEmpty()
        }
        if (pureRemoveHunks.isEmpty()) return emptyList()

        val results = mutableListOf<ChangedSymbol>()
        for (hunk in pureRemoveHunks) {
            for (lineContent in hunk.removedContent) {
                val decl = matchDeclaration(lineContent) ?: continue
                results.add(
                    ChangedSymbol(
                        name = decl.name,
                        qualifiedName = null,
                        kind = decl.kind,
                        filePath = file.projectRelativePath,
                        changeType = ChangeType.DELETED,
                        startLineInNewFile = -1,
                        signatureHint = lineContent.trim()
                    )
                )
                break
            }
        }
        return results
    }

    private data class DeclMatch(val name: String, val kind: SymbolKind)

    private fun matchDeclaration(line: String): DeclMatch? {
        DECL_KOTLIN_REGEX.find(line)?.let { m ->
            return DeclMatch(m.groupValues[2], kindFromKeyword(m.groupValues[1]))
        }
        DECL_JAVA_CLASS_REGEX.find(line)?.let { m ->
            return DeclMatch(m.groupValues[2], kindFromKeyword(m.groupValues[1]))
        }
        DECL_JAVA_METHOD_REGEX.find(line)?.let { m ->
            return DeclMatch(m.groupValues[1], SymbolKind.METHOD)
        }
        return null
    }

    private fun namedMemberAtLine(psiFile: PsiFile, document: Document, line: Int): PsiNamedElement? {
        if (line < 1 || line > document.lineCount) return null
        val lineStart = document.getLineStartOffset(line - 1)
        val lineEnd = document.getLineEndOffset(line - 1)
        val element = findFirstNonWhitespaceElement(psiFile, lineStart, lineEnd) ?: return null
        return findInnermostMember(element)
    }

    private fun findFirstNonWhitespaceElement(psiFile: PsiFile, lineStart: Int, lineEnd: Int): PsiElement? {
        var offset = lineStart
        while (offset < lineEnd) {
            val el = psiFile.findElementAt(offset)
            if (el != null && el.text.isNotBlank()) return el
            offset++
        }
        return psiFile.findElementAt(lineStart)
    }

    private fun findInnermostMember(element: PsiElement): PsiNamedElement? {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            if (current is PsiNamedElement) {
                val named: PsiNamedElement = current
                if (!named.name.isNullOrBlank() && (current is PsiMember || isKotlinMemberLike(current))) {
                    return named
                }
            }
            current = current.parent
        }
        return null
    }

    private fun isKotlinMemberLike(element: PsiElement): Boolean {
        val className = element.javaClass.simpleName
        if (className !in KOTLIN_DECL_NAMES) return false
        val parentName = element.parent?.javaClass?.simpleName ?: return false
        return parentName in KOTLIN_MEMBER_PARENTS
    }

    private fun collapseToInnermost(candidates: Collection<PsiNamedElement>): List<PsiNamedElement> {
        val list = candidates.toList()
        if (list.size <= 1) return list
        val ranges = list.map { it.textRange }
        return list.filterIndexed { i, _ ->
            val outer = ranges[i] ?: return@filterIndexed false
            list.indices.none { j ->
                if (j == i) return@none false
                val inner = ranges[j] ?: return@none false
                outer.contains(inner) && outer != inner
            }
        }
    }

    private fun buildChangedSymbol(
        element: PsiNamedElement,
        filePath: String,
        changeType: ChangeType,
        startLine: Int
    ): ChangedSymbol {
        val name = element.name ?: "<anonymous>"
        val kind = kindOf(element)
        val qualifiedName = qualifiedNameOf(element)
        val signatureHint = signatureHintOf(element)
        return ChangedSymbol(
            name = name,
            qualifiedName = qualifiedName,
            kind = kind,
            filePath = filePath,
            changeType = changeType,
            startLineInNewFile = startLine,
            signatureHint = signatureHint
        )
    }

    private fun kindOf(element: PsiNamedElement): SymbolKind {
        val classHierarchy = generateSequence<Class<*>>(element::class.java) { it.superclass }
            .flatMap { sequenceOf(it) + it.interfaces.asSequence() }
            .map { it.simpleName }
            .toList()
        return when {
            classHierarchy.any { it == "PsiMethod" || it.contains("Method") } -> SymbolKind.METHOD
            classHierarchy.any { it.contains("Function") } -> SymbolKind.FUNCTION
            classHierarchy.any { it == "PsiClass" } -> {
                when {
                    classHierarchy.any { it.contains("Interface") } -> SymbolKind.INTERFACE
                    classHierarchy.any { it.contains("Enum") } -> SymbolKind.ENUM
                    else -> SymbolKind.CLASS
                }
            }
            classHierarchy.any { it.contains("Interface") } -> SymbolKind.INTERFACE
            classHierarchy.any { it.contains("Enum") } -> SymbolKind.ENUM
            classHierarchy.any { it.contains("Property") } -> SymbolKind.PROPERTY
            classHierarchy.any { it.contains("Field") } -> SymbolKind.FIELD
            classHierarchy.any { it.contains("Class") } -> SymbolKind.CLASS
            else -> SymbolKind.OTHER
        }
    }

    private fun qualifiedNameOf(element: PsiNamedElement): String? {
        return try {
            val method = element.javaClass.methods.firstOrNull {
                it.name == "getQualifiedName" && it.parameterCount == 0
            } ?: return null
            method.invoke(element) as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun signatureHintOf(element: PsiNamedElement): String {
        val text = (element as? PsiElement)?.text ?: return element.name.orEmpty()
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: element.name.orEmpty()
        val bodyIndex = firstLine.indexOf('{')
        val trimmed = if (bodyIndex >= 0) firstLine.substring(0, bodyIndex).trim() else firstLine.trim()
        return trimmed.take(200)
    }

    private fun kindFromKeyword(keyword: String): SymbolKind = when (keyword) {
        "fun" -> SymbolKind.FUNCTION
        "class" -> SymbolKind.CLASS
        "interface" -> SymbolKind.INTERFACE
        "object" -> SymbolKind.CLASS
        "enum" -> SymbolKind.ENUM
        "val", "var" -> SymbolKind.PROPERTY
        else -> SymbolKind.OTHER
    }

    companion object {
        private val DECL_KOTLIN_REGEX = Regex(
            """\b(fun|class|interface|object|enum|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        private val DECL_JAVA_CLASS_REGEX = Regex(
            """\b(class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        private val DECL_JAVA_METHOD_REGEX = Regex(
            """(?:public|private|protected|static|final|abstract|synchronized|native|default)\s+(?:[\w<>\[\], ]+\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\("""
        )
        private val KOTLIN_DECL_NAMES = setOf(
            "KtNamedFunction", "KtClass", "KtObjectDeclaration",
            "KtProperty", "KtTypeAlias", "KtEnumEntry"
        )
        private val KOTLIN_MEMBER_PARENTS = setOf(
            "KtFile", "KtClassBody"
        )
    }
}
