package com.nasller.codeglance.panel

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.PersistentFSConstants
import com.intellij.util.ui.ImageUtil
import com.nasller.codeglance.concurrent.DirtyLock
import com.nasller.codeglance.config.Config
import com.nasller.codeglance.config.ConfigService.Companion.ConfigInstance
import com.nasller.codeglance.render.Minimap
import com.nasller.codeglance.render.OldMinimap
import com.nasller.codeglance.render.ScrollState
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import javax.swing.JPanel

sealed class AbstractGlancePanel<T>(private val project: Project, textEditor: TextEditor) : JPanel(), Disposable {
    protected val editor = textEditor.editor as EditorEx
    protected var mapRef = SoftReference<T>(null)
    protected val config: Config = ConfigInstance.state
    protected val renderLock = DirtyLock()
    protected val scrollState = ScrollState()
    protected val changeListManager: ChangeListManagerImpl = ChangeListManagerImpl.getInstanceImpl(project)
    protected val trackerManager = LineStatusTrackerManager.getInstance(project)
    private var buf: BufferedImage? = null
    protected var scrollbar:Scrollbar? = null
    protected var updateTask: ReadTask? = null

    // Anonymous Listeners that should be cleaned up.
    private val componentListener: ComponentListener
    private val documentListener: DocumentListener
    private val areaListener: VisibleAreaListener
    private val selectionListener: SelectionListener

    private val isDisabled: Boolean
        get() = editor.document.textLength > PersistentFSConstants.getMaxIntellisenseFileSize() || editor.document.lineCount < config.minLineCount
                || (parent != null && (parent.width == 0 || parent.width < config.minWindowWidth))

    init {
        componentListener = object : ComponentAdapter() {
            override fun componentResized(componentEvent: ComponentEvent?) = updateImage()
        }
        editor.contentComponent.addComponentListener(componentListener)

        documentListener = object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {}

            override fun documentChanged(event: DocumentEvent) = updateImage()
        }
        editor.document.addDocumentListener(documentListener)

        areaListener = VisibleAreaListener{
            scrollState.recomputeVisible(it.newRectangle)
            repaint()
        }
        editor.scrollingModel.addVisibleAreaListener(areaListener)

        selectionListener = object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = repaint()
        }
        editor.selectionModel.addSelectionListener(selectionListener)
        isOpaque = false
        layout = BorderLayout()
    }

    fun refresh() {
        updateImage()
        updateSize()
        parent?.revalidate()
    }

    /**
     * Adjusts the panels size to be a percentage of the total window
     */
    private fun updateSize() {
        preferredSize = if (isDisabled) {
            Dimension(0, 0)
        } else {
            Dimension(config.width, 0)
        }
    }

    /**
     * Fires off a new task to the worker thread. This should only be called from the ui thread.
     */
    protected fun updateImage() {
        if (isDisabled) return
        if (project.isDisposed) return
        if (!renderLock.acquire()) return

        ProgressIndicatorUtils.scheduleWithWriteActionPriority(updateTask!!)
    }

    protected fun updateImageSoon() = ApplicationManager.getApplication().invokeLater(this::updateImage)

    private fun paintLast(gfx: Graphics?) {
        val g = gfx as Graphics2D

        if (buf != null) {
            g.drawImage(buf,
                0, 0, buf!!.width, buf!!.height,
                0, 0, buf!!.width, buf!!.height,
                null)
        }
        paintSelections(g)
        paintVcs(g)
        scrollbar!!.paint(gfx)
    }

    abstract fun paintVcs(g: Graphics2D)

    abstract fun paintSelection(g: Graphics2D, startByte: Int, endByte: Int)

    private fun paintSelections(g: Graphics2D) {
        for ((index, start) in editor.selectionModel.blockSelectionStarts.withIndex()) {
            paintSelection(g, start, editor.selectionModel.blockSelectionEnds[index])
        }
    }

    abstract fun getOrCreateMap() : T

    override fun paint(gfx: Graphics?) {
        if (renderLock.locked) {
            paintLast(gfx)
            return
        }
        val get = mapRef.get()
        if (get == null) {
            updateImageSoon()
            paintLast(gfx)
            return
        }

        if (buf == null || buf?.width!! < width || buf?.height!! < height) {
            // TODO: Add handling for HiDPI scaling and switch back to UIUtil.createImage
            buf = ImageUtil.createImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
        }

        val g = buf!!.createGraphics()

        g.composite = AlphaComposite.getInstance(AlphaComposite.CLEAR)
        g.fillRect(0, 0, width, height)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)

        if (editor.document.textLength != 0) {
            g.drawImage(
                when (get) {
                    is OldMinimap -> {get.img!!}
                    is Minimap -> {get.img!!}
                    else -> throw RuntimeException("error img")
                },
                0, 0, scrollState.documentWidth, scrollState.drawHeight,
                0, scrollState.visibleStart, scrollState.documentWidth, scrollState.visibleEnd,
                null
            )
        }
        paintSelections(gfx as Graphics2D)
        paintVcs(gfx)
        gfx.drawImage(buf, 0, 0, null)
        scrollbar!!.paint(gfx)
    }

    override fun dispose() {
        editor.contentComponent.removeComponentListener(componentListener)
        editor.document.removeDocumentListener(documentListener)
        editor.scrollingModel.removeVisibleAreaListener(areaListener)
        editor.selectionModel.removeSelectionListener(selectionListener)
        scrollbar?.let {remove(it)}
        mapRef.clear()
    }
}