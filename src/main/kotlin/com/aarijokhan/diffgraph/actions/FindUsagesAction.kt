package com.aarijokhan.diffgraph.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.searches.ReferencesSearch

class FindUsagesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val offset = editor.caretModel.offset

        val result = ApplicationManager.getApplication().runReadAction<String> {
            val element = psiFile.findElementAt(offset)
            if (element == null) {
                return@runReadAction "No named element found at caret position"
            }

            val namedElement = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
            if (namedElement == null) {
                return@runReadAction "No named element found at caret position"
            }

            val elementName = namedElement.name ?: "Unknown"
            val elementType = namedElement::class.simpleName ?: "Unknown"

            val usages = ReferencesSearch.search(namedElement).findAll()
            val usageCount = usages.size

            val filePaths = usages
                .take(5)
                .mapNotNull { reference ->
                    reference.element.containingFile?.virtualFile?.path
                }
                .distinct()

            val filePathsText = if (filePaths.isNotEmpty()) {
                "\n\nFirst 5 usages in:\n" + filePaths.joinToString("\n")
            } else {
                ""
            }

            "Element: $elementName\nType: $elementType\nUsages: $usageCount$filePathsText"
        }

        Messages.showInfoMessage(
            project,
            result,
            "DiffGraph: Find Usages Count"
        )
    }
}
