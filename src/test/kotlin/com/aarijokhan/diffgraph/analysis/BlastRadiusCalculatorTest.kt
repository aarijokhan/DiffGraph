package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.ChangeType
import com.aarijokhan.diffgraph.model.ChangedSymbol
import com.aarijokhan.diffgraph.model.SymbolKind
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BlastRadiusCalculatorTest : BasePlatformTestCase() {

    private val calculator = BlastRadiusCalculator()

    fun testCountsUsagesAcrossChangedAndUnchangedFilesSeparately() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        addJava("pkg/ChangedCaller1.java", """
            package pkg;
            public class ChangedCaller1 { public void f() { new ClassA().alpha(); } }
        """.trimIndent())
        addJava("pkg/ChangedCaller2.java", """
            package pkg;
            public class ChangedCaller2 { public void f() { new ClassA().alpha(); } }
        """.trimIndent())
        addJava("pkg/Unchanged1.java", """
            package pkg;
            public class Unchanged1 { public void f() { new ClassA().alpha(); } }
        """.trimIndent())
        addJava("pkg/Unchanged2.java", """
            package pkg;
            public class Unchanged2 { public void f() { new ClassA().alpha(); } }
        """.trimIndent())
        addJava("pkg/Unchanged3.java", """
            package pkg;
            public class Unchanged3 { public void f() { new ClassA().alpha(); } }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val changed = setOf(
            alpha.filePath,
            "pkg/ChangedCaller1.java",
            "pkg/ChangedCaller2.java"
        )
        val entries = calculator.calculate(project, listOf(alpha), changed)
        val entry = entries.single()
        assertEquals(2, entry.usagesInChangedFiles)
        assertEquals(3, entry.usagesInUnchangedFiles)
        assertEquals(5, entry.totalUsages)
    }

    fun testReturnsEntriesSortedByTotalDescending() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        val fileB = addJava("pkg/ClassB.java", """
            package pkg;
            public class ClassB { public void beta() {} }
        """.trimIndent())
        addJava("pkg/Caller1.java", """
            package pkg;
            public class Caller1 { public void f() { new ClassA().alpha(); new ClassB().beta(); } }
        """.trimIndent())
        addJava("pkg/Caller2.java", """
            package pkg;
            public class Caller2 { public void f() { new ClassA().alpha(); } }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val beta = symbolFor(fileB, "beta")
        val entries = calculator.calculate(project, listOf(beta, alpha), setOf(alpha.filePath, beta.filePath))
        assertEquals(listOf("alpha", "beta"), entries.map { it.symbol.name })
        assertTrue(entries[0].totalUsages >= entries[1].totalUsages)
    }

    fun testSymbolWithNoUsagesReturnsZeroEntry() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        val alpha = symbolFor(fileA, "alpha")
        val entries = calculator.calculate(project, listOf(alpha), setOf(alpha.filePath))
        val entry = entries.single()
        assertEquals(0, entry.totalUsages)
        assertEquals(0, entry.usagesInChangedFiles)
        assertEquals(0, entry.usagesInUnchangedFiles)
        assertTrue(entry.locations.isEmpty())
    }

    fun testCapsLocationsAtFifty() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        val callers = StringBuilder("package pkg;\npublic class Mega {\n")
        for (i in 1..60) callers.append("  public void m$i() { new ClassA().alpha(); }\n")
        callers.append("}\n")
        addJava("pkg/Mega.java", callers.toString())

        val alpha = symbolFor(fileA, "alpha")
        val entry = calculator.calculate(project, listOf(alpha), setOf(alpha.filePath)).single()
        assertEquals(60, entry.totalUsages)
        assertEquals(BlastRadiusCalculator.LOCATIONS_CAP, entry.locations.size)
    }

    fun testOrdersLocationsChangedFirstThenByFilePath() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        addJava("pkg/ZChanged.java", """
            package pkg;
            public class ZChanged { public void f() { new ClassA().alpha(); } }
        """.trimIndent())
        addJava("pkg/AUnchanged.java", """
            package pkg;
            public class AUnchanged { public void f() { new ClassA().alpha(); } }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val entry = calculator.calculate(
            project,
            listOf(alpha),
            setOf(alpha.filePath, "pkg/ZChanged.java")
        ).single()
        val firstPaths = entry.locations.map { it.filePath }
        assertTrue(
            "changed-file location should come first: $firstPaths",
            firstPaths.first() == "pkg/ZChanged.java"
        )
    }

    fun testSortsEntriesWithSameTotalByUnchangedDesc() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        val fileB = addJava("pkg/ClassB.java", """
            package pkg;
            public class ClassB { public void beta() {} }
        """.trimIndent())
        addJava("pkg/AlphaUser.java", """
            package pkg;
            public class AlphaUser { public void f() { new ClassA().alpha(); } }
        """.trimIndent())
        addJava("pkg/BetaUser.java", """
            package pkg;
            public class BetaUser { public void f() { new ClassB().beta(); } }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val beta = symbolFor(fileB, "beta")
        // alpha's caller is changed, beta's caller is unchanged
        val changed = setOf(alpha.filePath, beta.filePath, "pkg/AlphaUser.java")
        val entries = calculator.calculate(project, listOf(alpha, beta), changed)
        // Same total (1 each); beta has more unchanged usages -> beta first
        assertEquals("beta should come first (more unchanged usages)", "beta", entries[0].symbol.name)
    }

    private fun addJava(path: String, text: String): PsiFile =
        myFixture.addFileToProject(path, text)

    private fun symbolFor(psiFile: PsiFile, name: String): ChangedSymbol {
        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
        val element = PsiTreeUtil.findChildrenOfType(psiFile, PsiNamedElement::class.java)
            .first { it.name == name }
        val startOffset = (element as PsiElement).textRange.startOffset
        val startLine = doc.getLineNumber(startOffset) + 1
        return ChangedSymbol(
            name = name,
            qualifiedName = null,
            kind = SymbolKind.METHOD,
            filePath = relativePathOf(psiFile.virtualFile),
            changeType = ChangeType.MODIFIED,
            startLineInNewFile = startLine,
            signatureHint = name
        )
    }

    private fun relativePathOf(vFile: VirtualFile): String {
        val base = project.basePath
        if (base != null && vFile.path.startsWith(base)) {
            val rel = vFile.path.removePrefix(base).trimStart('/')
            if (rel.isNotEmpty()) return rel
        }
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
        for (root in contentRoots) {
            VfsUtilCore.getRelativePath(vFile, root)?.let { return it }
        }
        return vFile.path
    }
}
