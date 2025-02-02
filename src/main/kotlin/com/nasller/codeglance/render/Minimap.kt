package com.nasller.codeglance.render

import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.view.IterationState
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.ui.ImageUtil
import com.nasller.codeglance.CodeGlancePlugin
import com.nasller.codeglance.config.Config
import com.nasller.codeglance.panel.AbstractGlancePanel
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * A rendered minimap of a document
 */
class Minimap(private val config: Config, glancePanel: AbstractGlancePanel) {
	var img: BufferedImage? = null
	private val editor = glancePanel.editor

	@Synchronized
	fun update(scrollState: ScrollState, indicator: ProgressIndicator) {
		if (img == null || img!!.height < scrollState.documentHeight || img!!.width < config.width) {
			if (img != null) img!!.flush()
			// Create an image that is a bit bigger then the one we need, so we don't need to re-create it again soon.
			// Documents can get big, so rather than relative sizes lets just add a fixed amount on.
			img = BufferedImage(config.width, scrollState.documentHeight + (100 * config.pixelsPerLine), BufferedImage.TYPE_4BYTE_ABGR)
		}

		val g = img!!.createGraphics()
		g.composite = CLEAR
		g.fillRect(0, 0, img!!.width, img!!.height)

		// These are just to reduce allocations. Premature optimization???
		val colorBuffer = FloatArray(4)
		val scaleBuffer = FloatArray(4)

		val text = editor.document.charsSequence
		val line = editor.document.createLineIterator()
		val hlIter = editor.highlighter.createIterator(0)

		var x = 0
		var y: Int
		var prevY = -1
		var foldedLines = 0
		indicator.checkCanceled()
		while (!hlIter.atEnd()) {
			val tokenStart = hlIter.start
			var i = tokenStart
			var hasColor = false
			line.start(tokenStart)
			y = (line.lineNumber - foldedLines) * config.pixelsPerLine
			try {
				hlIter.textAttributes.foregroundColor?.let {
					it.getRGBComponents(colorBuffer)
					hasColor = true
				}
			} catch (_: Exception){ }
			// Jump over folds
			val checkFold = {
				var isFolded = editor.foldingModel.isOffsetCollapsed(i)
				if (isFolded) {
					val fold = editor.foldingModel.getCollapsedRegionAtOffset(i)!!
					if(!CodeGlancePlugin.isCustomFoldRegionImpl(fold) &&
						fold.startOffset >= 0 && fold.endOffset >= 0){
						foldedLines += editor.document.getLineNumber(fold.endOffset) - editor.document.getLineNumber(fold.startOffset)
						i = fold.endOffset
					}else{
						isFolded = false
					}
				}
				isFolded
			}
			// New line, pre-loop to count whitespace from start of line.
			if (y != prevY) {
				x = 0
				i = line.start
				while (i < tokenStart) {
					if (checkFold()) break
					x += if (text[i++] == '\t') 4 else 1
					// Abort if this line is getting too long...
					if (x > config.width) break
				}
			}
			while (i < hlIter.end) {
				if (checkFold()) break
				// Watch out for tokens that extend past the document... bad plugins? see issue #138
				if (i >= text.length) return
				when (text[i]) {
					'\n' -> {
						x = 0
						y += config.pixelsPerLine
					}
					'\t' -> x += 4
					else -> x += 1
				}
				if (0 <= x && x < img!!.width && 0 <= y && y + config.pixelsPerLine < img!!.height) {
					if(!hasColor)getColor(i).getRGBComponents(colorBuffer)
					if (config.clean) {
						renderClean(x, y, text[i].code, colorBuffer, scaleBuffer)
					} else {
						renderAccurate(x, y, text[i].code, colorBuffer, scaleBuffer)
					}
				}
				++i
			}
			prevY = y
			do // Skip to end of fold
				hlIter.advance()
			while (!hlIter.atEnd() && hlIter.start < i)
		}
	}

	private fun getColor(offset:Int):Color{
		var color:Color? = null
		val list = mutableListOf<RangeHighlighterEx>()
		editor.filteredDocumentMarkupModel.processRangeHighlightersOverlappingWith(max(0, offset - 1),offset) {
			if (it.isValid && it.getTextAttributes (editor.colorsScheme) != TextAttributes.ERASE_MARKER) {
				list.add(it)
			}
			return@processRangeHighlightersOverlappingWith true
		}
		list.apply {
			if(this.size > 1) ContainerUtil.quickSort(this,IterationState.createByLayerThenByAttributesComparator(editor.colorsScheme))
		}.forEach{
			it.getTextAttributes(editor.colorsScheme)?.foregroundColor?.run {
				color = this
				return@forEach
			}
		}
		return color?:editor.colorsScheme.defaultForeground
	}

	private fun renderClean(x: Int, y: Int, char: Int, color: FloatArray, buffer: FloatArray) {
		val weight = when (char) {
			in 0..32 -> 0.0f
			in 33..126 -> 0.8f
			else -> 0.4f
		}
		if (weight == 0.0f) return
		when (config.pixelsPerLine) {
			1 -> // Can't show whitespace between lines anymore. This looks rather ugly...
				setPixel(x, y + 1, color, weight * 0.6f, buffer)
			2 -> {
				// Two lines we make the top line a little lighter to give the illusion of whitespace between lines.
				setPixel(x, y, color, weight * 0.3f, buffer)
				setPixel(x, y + 1, color, weight * 0.6f, buffer)
			}
			3 -> {
				// Three lines we make the top nearly empty, and fade the bottom a little too
				setPixel(x, y, color, weight * 0.1f, buffer)
				setPixel(x, y + 1, color, weight * 0.6f, buffer)
				setPixel(x, y + 2, color, weight * 0.6f, buffer)
			}
			4 -> {
				// Empty top line, Nice blend for everything else
				setPixel(x, y + 1, color, weight * 0.6f, buffer)
				setPixel(x, y + 2, color, weight * 0.6f, buffer)
				setPixel(x, y + 3, color, weight * 0.6f, buffer)
			}
		}
	}

	private fun renderAccurate(x: Int, y: Int, char: Int, color: FloatArray, buffer: FloatArray) {
		val topWeight = getTopWeight(char)
		val bottomWeight = getBottomWeight(char)
		// No point rendering non-visible characters.
		if (topWeight == 0.0f && bottomWeight == 0.0f) return
		when (config.pixelsPerLine) {
			1 -> // Can't show whitespace between lines anymore. This looks rather ugly...
				setPixel(x, y + 1, color, ((topWeight + bottomWeight) / 2.0).toFloat(), buffer)
			2 -> {
				// Two lines we make the top line a little lighter to give the illusion of whitespace between lines.
				setPixel(x, y, color, topWeight * 0.5f, buffer)
				setPixel(x, y + 1, color, bottomWeight, buffer)
			}
			3 -> {
				// Three lines we make the top nearly empty, and fade the bottom a little too
				setPixel(x, y, color, topWeight * 0.3f, buffer)
				setPixel(x, y + 1, color, ((topWeight + bottomWeight) / 2.0).toFloat(), buffer)
				setPixel(x, y + 2, color, bottomWeight * 0.7f, buffer)
			}
			4 -> {
				// Empty top line, Nice blend for everything else
				setPixel(x, y + 1, color, topWeight, buffer)
				setPixel(x, y + 2, color, ((topWeight + bottomWeight) / 2.0).toFloat(), buffer)
				setPixel(x, y + 3, color, bottomWeight, buffer)
			}
		}
	}

	/**
	 * mask out the alpha component and set it to the given value.
	 * @param rgba      Color A
	 * *
	 * @param alpha     alpha percent from 0-1.
	 */
	private fun setPixel(x: Int, y: Int, rgba: FloatArray, alpha: Float, scaleBuffer: FloatArray) {
		for (i in 0..2) scaleBuffer[i] = rgba[i] * 0xFF
		scaleBuffer[3] = when {
			alpha > 1 -> rgba[3]
			else -> max(alpha, 0f)
		} * 0xFF
		img!!.raster.setPixel(x, y, scaleBuffer)
	}

	companion object {
		private val CLEAR = AlphaComposite.getInstance(AlphaComposite.CLEAR)
	}
}