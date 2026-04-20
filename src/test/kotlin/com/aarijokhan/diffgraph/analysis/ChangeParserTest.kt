package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.ChangeType
import com.aarijokhan.diffgraph.model.ChangedFile
import com.aarijokhan.diffgraph.model.DiffHunk
import com.aarijokhan.diffgraph.model.SymbolKind
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ChangeParserTest : BasePlatformTestCase() {

    private val parser = ChangeParser()

    fun testParsesSingleAddedMethodFromAddedHunk() {
        val text = """
            |public class Foo {
            |    public void alpha() {
            |        System.out.println("hi");
            |    }
            |}
        """.trimMargin()
        val psi = configureJava("Foo.java", text)
        val file = changedFile(
            path = "Foo.java",
            changeType = ChangeType.ADDED,
            hunks = listOf(
                hunk(
                    newStart = 1,
                    newCount = 5,
                    addedLineNumbers = listOf(1, 2, 3, 4, 5),
                    addedContent = text.split("\n")
                )
            )
        )

        val symbols = runParse(psi, file)
        assertEquals("expected one symbol, got $symbols", 1, symbols.size)
        val alpha = symbols.single()
        assertEquals("alpha", alpha.name)
        assertEquals(SymbolKind.METHOD, alpha.kind)
        assertEquals(ChangeType.ADDED, alpha.changeType)
    }

    fun testParsesModifiedMethodWhenHunkStraddlesBody() {
        val text = """
            |public class Foo {
            |    public void alpha() {
            |        System.out.println("new");
            |    }
            |}
        """.trimMargin()
        val psi = configureJava("Foo.java", text)
        val file = changedFile(
            path = "Foo.java",
            changeType = ChangeType.MODIFIED,
            hunks = listOf(
                hunk(
                    newStart = 3,
                    newCount = 1,
                    oldStart = 3,
                    oldCount = 1,
                    addedLineNumbers = listOf(3),
                    removedLineNumbers = listOf(3),
                    addedContent = listOf("        System.out.println(\"new\");"),
                    removedContent = listOf("        System.out.println(\"old\");")
                )
            )
        )

        val symbols = runParse(psi, file)
        assertEquals(1, symbols.size)
        val alpha = symbols.single()
        assertEquals("alpha", alpha.name)
        assertEquals(SymbolKind.METHOD, alpha.kind)
        assertEquals(ChangeType.MODIFIED, alpha.changeType)
    }

    fun testEmitsSmallestEnclosingSymbol() {
        val text = """
            |public class Wrapper {
            |    public void inner() {
            |        int x = 42;
            |    }
            |}
        """.trimMargin()
        val psi = configureJava("Wrapper.java", text)
        val file = changedFile(
            path = "Wrapper.java",
            changeType = ChangeType.MODIFIED,
            hunks = listOf(
                hunk(
                    newStart = 3,
                    newCount = 1,
                    oldStart = 3,
                    oldCount = 1,
                    addedLineNumbers = listOf(3),
                    removedLineNumbers = listOf(3),
                    addedContent = listOf("        int x = 42;"),
                    removedContent = listOf("        int x = 1;")
                )
            )
        )

        val symbols = runParse(psi, file)
        val names = symbols.map { it.name }.toSet()
        assertTrue("expected inner method to be present, got $names", "inner" in names)
        assertFalse("should not emit the wrapping class when a method is the smallest container", "Wrapper" in names)
    }

    fun testSkipsFileWithoutPsiSupport() {
        val file = ChangedFile(
            projectRelativePath = "bin.dat",
            absolutePath = "/tmp/bin.dat",
            changeType = ChangeType.MODIFIED,
            previousProjectRelativePath = null,
            diffHunks = listOf(hunk(newStart = 1, newCount = 1, addedLineNumbers = listOf(1), addedContent = listOf("x"))),
            isBinary = true,
            hasPsiSupport = false
        )
        val symbols = parser.parseSymbols(project, listOf(file))
        assertTrue("should return empty for file without PSI support", symbols.isEmpty())
    }

    fun testDistinguishesAddedDeletedModifiedByHunkIntersection() {
        val text = """
            |public class Foo {
            |    public void alpha() {
            |        System.out.println("modified");
            |    }
            |    public void bravo() {
            |        System.out.println("new");
            |    }
            |}
        """.trimMargin()
        val psi = configureJava("Foo.java", text)
        val file = changedFile(
            path = "Foo.java",
            changeType = ChangeType.MODIFIED,
            hunks = listOf(
                // Mixed hunk inside alpha body
                hunk(
                    newStart = 3,
                    newCount = 1,
                    oldStart = 3,
                    oldCount = 1,
                    addedLineNumbers = listOf(3),
                    removedLineNumbers = listOf(3),
                    addedContent = listOf("        System.out.println(\"modified\");"),
                    removedContent = listOf("        System.out.println(\"old\");")
                ),
                // Pure-add hunk covering bravo
                hunk(
                    newStart = 5,
                    newCount = 3,
                    oldStart = 4,
                    oldCount = 0,
                    addedLineNumbers = listOf(5, 6, 7),
                    addedContent = listOf(
                        "    public void bravo() {",
                        "        System.out.println(\"new\");",
                        "    }"
                    )
                ),
                // Pure-remove hunk (a method "charlie" was deleted)
                hunk(
                    newStart = 8,
                    newCount = 0,
                    oldStart = 8,
                    oldCount = 3,
                    removedLineNumbers = listOf(8, 9, 10),
                    removedContent = listOf(
                        "    public void charlie() {",
                        "        System.out.println(\"gone\");",
                        "    }"
                    )
                )
            )
        )

        val symbols = runParse(psi, file)
        val byName = symbols.associateBy { it.name }

        assertTrue("should find alpha MODIFIED: got $symbols", byName["alpha"]?.changeType == ChangeType.MODIFIED)
        assertTrue("should find bravo ADDED: got $symbols", byName["bravo"]?.changeType == ChangeType.ADDED)
        assertTrue("should find charlie DELETED via regex: got $symbols", byName["charlie"]?.changeType == ChangeType.DELETED)
    }

    fun testCapturesSignatureHintForMethod() {
        val text = """
            |public class Foo {
            |    public int compute(int n) {
            |        return n * 2;
            |    }
            |}
        """.trimMargin()
        val psi = configureJava("Foo.java", text)
        val file = changedFile(
            path = "Foo.java",
            changeType = ChangeType.MODIFIED,
            hunks = listOf(
                hunk(
                    newStart = 3,
                    newCount = 1,
                    oldStart = 3,
                    oldCount = 1,
                    addedLineNumbers = listOf(3),
                    removedLineNumbers = listOf(3),
                    addedContent = listOf("        return n * 2;"),
                    removedContent = listOf("        return n;")
                )
            )
        )

        val symbols = runParse(psi, file)
        val compute = symbols.single { it.name == "compute" }
        assertFalse("signatureHint should be non-empty", compute.signatureHint.isBlank())
        assertTrue(
            "signatureHint should reference method name, got: ${compute.signatureHint}",
            compute.signatureHint.contains("compute")
        )
    }

    fun testChangedSymbolEquality() {
        val a = com.aarijokhan.diffgraph.model.ChangedSymbol(
            name = "foo",
            qualifiedName = null,
            kind = SymbolKind.METHOD,
            filePath = "Foo.java",
            changeType = ChangeType.MODIFIED,
            startLineInNewFile = 1,
            signatureHint = "foo()"
        )
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    private fun configureJava(name: String, text: String): PsiFile {
        return myFixture.configureByText(name, text)
    }

    private fun runParse(psiFile: PsiFile, file: ChangedFile): List<com.aarijokhan.diffgraph.model.ChangedSymbol> {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: error("no document for ${psiFile.name}")
        val present = parser.parsePresentSymbols(psiFile, document, file)
        val deleted = parser.parseDeletedSymbols(file)
        return present + deleted
    }

    private fun changedFile(
        path: String,
        changeType: ChangeType,
        hunks: List<DiffHunk>
    ): ChangedFile = ChangedFile(
        projectRelativePath = path,
        absolutePath = "/light/$path",
        changeType = changeType,
        previousProjectRelativePath = null,
        diffHunks = hunks,
        isBinary = false,
        hasPsiSupport = true
    )

    private fun hunk(
        newStart: Int,
        newCount: Int,
        oldStart: Int = newStart,
        oldCount: Int = 0,
        addedLineNumbers: List<Int> = emptyList(),
        removedLineNumbers: List<Int> = emptyList(),
        addedContent: List<String> = emptyList(),
        removedContent: List<String> = emptyList()
    ): DiffHunk = DiffHunk(
        oldStartLine = oldStart,
        oldLineCount = oldCount,
        newStartLine = newStart,
        newLineCount = newCount,
        addedLineNumbers = addedLineNumbers,
        removedLineNumbers = removedLineNumbers,
        addedContent = addedContent,
        removedContent = removedContent
    )
}
