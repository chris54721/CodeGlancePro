package com.nasller.codeglance.panel

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.PersistentFSConstants
import com.intellij.util.ui.ImageUtil
import com.nasller.codeglance.CodeGlancePlugin.Companion.isCustomFoldRegionImpl
import com.nasller.codeglance.concurrent.DirtyLock
import com.nasller.codeglance.config.Config
import com.nasller.codeglance.config.ConfigService.Companion.ConfigInstance
import com.nasller.codeglance.render.ScrollState
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.image.BufferedImage
import javax.swing.JPanel

sealed class AbstractGlancePanel(val project: Project, textEditor: TextEditor) : JPanel(), Disposable {
    val editor = textEditor.editor as EditorEx
    val originalScrollbarWidth = editor.scrollPane.verticalScrollBar.preferredSize.width
    protected val config: Config = ConfigInstance.state
    protected val renderLock = DirtyLock()
    protected val scrollState = ScrollState()
    protected val changeListManager: ChangeListManagerImpl = ChangeListManagerImpl.getInstanceImpl(project)
    protected val trackerManager = LineStatusTrackerManager.getInstance(project)
    // Anonymous Listeners that should be cleaned up.
    private val componentListener: ComponentListener
    private val documentListener: DocumentListener
    private val areaListener: VisibleAreaListener
    private val selectionListener: SelectionListener
    private val updateTask: ReadTask = object :ReadTask() {
        override fun onCanceled(indicator: ProgressIndicator) {
            renderLock.release()
            renderLock.clean()
            updateImageSoon()
        }

        override fun computeInReadAction(indicator: ProgressIndicator) {
            this@AbstractGlancePanel.computeInReadAction(indicator)
        }
    }
    private val isDisabled: Boolean
        get() = config.disabled || editor.document.textLength > PersistentFSConstants.getMaxIntellisenseFileSize() || editor.document.lineCount < config.minLineCount
                || (parent != null && (parent.width == 0 || parent.width < config.minWindowWidth))
    private var buf: BufferedImage? = null
    protected var scrollbar:ScrollBar? = null

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

    fun updateImageSoon() = ApplicationManager.getApplication().invokeLater(this::updateImage)

    /**
     * Fires off a new task to the worker thread. This should only be called from the ui thread.
     */
    protected fun updateImage() {
        if (isDisabled) return
        if (project.isDisposed) return
        if (!renderLock.acquire()) return
        ProgressIndicatorUtils.scheduleWithWriteActionPriority(updateTask)
    }

    private fun paintLast(gfx: Graphics?) {
        val g = gfx as Graphics2D
        buf?.run{ g.drawImage(this,0, 0, width, height, 0, 0, width, height,null) }
        paintSelections(g)
        paintVcs(g)
        scrollbar!!.paint(gfx)
    }

    protected fun getDocumentRenderLine(lineStart:Int,lineEnd:Int):Pair<Int,Int>{
        var startAdd = 0
        var endAdd = 0
        editor.foldingModel.allFoldRegions.filter{ isCustomFoldRegionImpl(it) && !it.isExpanded &&
                it.startOffset >= 0 && it.endOffset >= 0}.forEach{
            val start = it.document.getLineNumber(it.startOffset)
            val end = it.document.getLineNumber(it.endOffset)
            val i = end - start
            if(lineStart < start && end < lineEnd){
                endAdd += i
            }else if(end < lineEnd){
                startAdd += i
                endAdd += i
            }
        }
        return Pair(startAdd,endAdd)
    }

    protected fun drawMarkupLine(it: RangeHighlighter, g: Graphics2D,compensateLine:Boolean){
        val textAttributes = it.getTextAttributes(editor.colorsScheme)
        g.color = it.getErrorStripeMarkColor(editor.colorsScheme) ?: textAttributes?.backgroundColor
                ?: textAttributes?.foregroundColor ?: return
        val documentLine = getDocumentRenderLine(editor.offsetToLogicalPosition(it.startOffset).line, editor.offsetToLogicalPosition(it.endOffset).line)
        val start = editor.offsetToVisualPosition(it.startOffset)
        val end = editor.offsetToVisualPosition(it.endOffset)
        var sX = if (start.column > (width - minGap)) width - minGap else start.column
        val sY = (start.line + documentLine.first) * config.pixelsPerLine - scrollState.visibleStart
        var eX = if (start.column < (width - minGap)) end.column + 1 else width
        val eY = (end.line + documentLine.second) * config.pixelsPerLine - scrollState.visibleStart
        val collapsed = editor.foldingModel.isOffsetCollapsed(it.startOffset)
        if (sY == eY && !collapsed) {
            val gap = eX - sX
            if(compensateLine && gap < minGap){
                eX += minGap-gap
                if(eX > width) sX -= eX - width
            }
            g.fillRect(sX, sY, eX - sX, config.pixelsPerLine)
        } else if(collapsed){
            g.fillRect(0, sY, width / 2, config.pixelsPerLine)
        } else {
            g.fillRect(sX, sY, width - sX, config.pixelsPerLine)
            g.fillRect(0, eY, eX, config.pixelsPerLine)
            if (eY + config.pixelsPerLine != sY) {
                g.fillRect(0, sY + config.pixelsPerLine, width, eY - sY - config.pixelsPerLine)
            }
        }
    }

    abstract fun computeInReadAction(indicator: ProgressIndicator)

    abstract fun paintVcs(g: Graphics2D)

    abstract fun paintSelection(g: Graphics2D, startByte: Int, endByte: Int)

    abstract fun paintCaretPosition(g: Graphics2D)

    abstract fun paintOtherHighlight(g: Graphics2D)

    abstract fun paintErrorStripes(g: Graphics2D)

    private fun paintSelections(g: Graphics2D) {
        if(editor.selectionModel.hasSelection()){
            for ((index, start) in editor.selectionModel.blockSelectionStarts.withIndex()) {
                paintSelection(g, start, editor.selectionModel.blockSelectionEnds[index])
            }
        }else{
            paintCaretPosition(g)
        }
    }

    override fun paint(gfx: Graphics?) {
        if (renderLock.locked) {
            paintLast(gfx)
            return
        }
        val img = getDrawImage() ?: return
        if (buf == null || buf?.width!! < width || buf?.height!! < height) {
            buf = BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
        }
        val g = buf!!.createGraphics()
        g.composite = AlphaComposite.getInstance(AlphaComposite.CLEAR)
        g.fillRect(0, 0, width, height)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)
        if (editor.document.textLength != 0) {
            g.drawImage(img, 0, 0, scrollState.documentWidth, scrollState.drawHeight,
                0, scrollState.visibleStart, scrollState.documentWidth, scrollState.visibleEnd, null)
        }
        paintVcs(gfx as Graphics2D)
        paintSelections(gfx)
        paintOtherHighlight(gfx)
        paintErrorStripes(gfx)
        gfx.composite = srcOver0_8
        gfx.drawImage(buf, 0, 0, null)
        scrollbar!!.paint(gfx)
    }

    abstract fun getDrawImage() : BufferedImage?

    override fun dispose() {
        editor.contentComponent.removeComponentListener(componentListener)
        editor.document.removeDocumentListener(documentListener)
        editor.scrollingModel.removeVisibleAreaListener(areaListener)
        editor.selectionModel.removeSelectionListener(selectionListener)
        scrollbar?.let {remove(it)}
    }

    protected companion object{
        const val minGap = 15
        val srcOver0_4: AlphaComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.40f)
        val srcOver0_8: AlphaComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f)
        val srcOver1: AlphaComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
    }
}