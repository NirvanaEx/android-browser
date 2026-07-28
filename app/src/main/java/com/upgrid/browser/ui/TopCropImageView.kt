package com.upgrid.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * An ImageView that scales a page screenshot to the full width and keeps the
 * TOP of it, letting the bottom fall outside the view.
 *
 * `centerCrop` is the obvious choice and the wrong one here. A phone
 * screenshot is roughly 9:20; a tab card is nowhere near that tall, so
 * centre-cropping throws away the header and the first screen of content —
 * exactly the part that makes a tab recognisable — and keeps a band from the
 * middle of the page. Anchoring to the top is what every browser's tab grid
 * does, and it's not expressible as a ScaleType, so it's a matrix.
 *
 * `fitStart` isn't the answer either: it fits the whole image *inside* the
 * box, so a tall screenshot ends up a narrow strip with empty space either
 * side.
 */
class TopCropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        scaleType = ScaleType.MATRIX
    }

    // setFrame rather than onLayout: the matrix has to be right before the
    // first draw, and setFrame is where the view learns its size.
    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        applyTopCrop(r - l, b - t)
        return super.setFrame(l, t, r, b)
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        applyTopCrop(width, height)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        applyTopCrop(width, height)
    }

    private fun applyTopCrop(viewWidth: Int, viewHeight: Int) {
        val image = drawable ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val sourceWidth = image.intrinsicWidth
        val sourceHeight = image.intrinsicHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        // Fill the width. If the result is shorter than the box (a wide
        // screenshot in a tall card) fall back to filling the height instead,
        // so there's never a blank band.
        val scale = maxOf(
            viewWidth.toFloat() / sourceWidth,
            if (sourceHeight * (viewWidth.toFloat() / sourceWidth) < viewHeight) {
                viewHeight.toFloat() / sourceHeight
            } else {
                0f
            },
        )

        imageMatrix = imageMatrix.apply {
            setScale(scale, scale)
            // Centre horizontally (only matters in the fill-height case),
            // top-align vertically.
            postTranslate((viewWidth - sourceWidth * scale) / 2f, 0f)
        }
    }
}
