package com.nasller.codeglance

import com.intellij.codeInsight.documentation.render.DocRenderManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.Key

class CodeGlancePlugin : ProjectManagerListener {
    override fun projectOpened(project: Project) {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            EditorPanelInjector(project)
        )
    }

    companion object{
        val DocRenderEnabled = try {
            val docRenderManagerClass = DocRenderManager::class.java
            val field = docRenderManagerClass.getDeclaredField("DOC_RENDER_ENABLED")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val key = field.get(null) as? Key<Boolean>
            field.isAccessible = false
            key
        } catch (e: Exception) {
            null
        }

        private val customFoldRegionImpl:Class<*>? = try {
            Class.forName("com.intellij.openapi.editor.impl.CustomFoldRegionImpl")
        } catch (e: Exception) {
            null
        }

        fun isCustomFoldRegionImpl(any: Any):Boolean = customFoldRegionImpl != null && customFoldRegionImpl.isInstance(any)
    }
}