package com.pdf.mylibrary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor

/**
 * Thread-safe wrapper around [PdfRenderer].
 *
 * IMPORTANT: android.graphics.pdf.PdfRenderer is NOT thread-safe and allows only ONE
 * open page at a time. Every access to the renderer is therefore serialized through
 * [lock], and a [closed] flag guarantees we never touch the native document after it
 * has been released. This is what prevents the crashes:
 *   - java.lang.NullPointerException: PdfDocumentProxy cannot be null
 *   - java.lang.IllegalStateException: Document already closed
 * which happen when a page is rendered on a background thread while another thread
 * closes the document.
 */
class RendererCore(private val fileDescriptor: ParcelFileDescriptor) {

    // PdfRenderer's constructor can throw on a corrupt/encrypted/non-PDF file. If it
    // does, close the descriptor here because close() will never be reached.
    private val pdfRenderer: PdfRenderer = try {
        PdfRenderer(fileDescriptor)
    } catch (t: Throwable) {
        try { fileDescriptor.close() } catch (_: Throwable) { }
        throw t
    }

    private val lock = Any()

    @Volatile
    private var closed = false

    fun getPageCount(): Int = synchronized(lock) {
        if (closed) 0 else pdfRenderer.pageCount
    }

    /** Aspect ratio (height / width) without rendering a bitmap. */
    fun getPageAspectRatio(pageIndex: Int): Float = synchronized(lock) {
        if (closed) return 1.4142f
        pdfRenderer.openPage(pageIndex).use { page ->
            val w = page.width.toFloat().coerceAtLeast(1f)
            page.height.toFloat() / w
        }
    }

    /**
     * Render a single page to a bitmap. Fully serialized: while this holds [lock],
     * close() cannot run, and vice-versa.
     *
     * @throws RendererClosedException if the document has already been closed. Callers
     *         on background threads should catch this and simply stop.
     */
    fun renderPage(pageIndex: Int, targetWidth: Int, scaleFactor: Float = 3f): Bitmap {
        synchronized(lock) {
            if (closed) throw RendererClosedException()

            val safeScale = scaleFactor.coerceIn(1f, 6f)
            pdfRenderer.openPage(pageIndex).use { page ->
                val safeTarget = targetWidth.coerceAtLeast(1)
                var width = (safeTarget * safeScale).toInt().coerceAtLeast(1)
                val pageW = page.width.toFloat().coerceAtLeast(1f)
                val ratio = page.height.toFloat() / pageW
                var height = (width * ratio).toInt().coerceAtLeast(1)

                // Clamp so we never ask Canvas to draw a bitmap it will reject
                // (RuntimeException: trying to draw too large bitmap) or blow the heap.
                val bytes = width.toLong() * height.toLong() * 4L
                if (bytes > MAX_BITMAP_BYTES) {
                    val shrink = kotlin.math.sqrt(MAX_BITMAP_BYTES.toDouble() / bytes.toDouble())
                    width = (width * shrink).toInt().coerceAtLeast(1)
                    height = (height * shrink).toInt().coerceAtLeast(1)
                }
                if (width > MAX_BITMAP_DIMENSION) {
                    height = (height * (MAX_BITMAP_DIMENSION.toDouble() / width)).toInt().coerceAtLeast(1)
                    width = MAX_BITMAP_DIMENSION
                }
                if (height > MAX_BITMAP_DIMENSION) {
                    width = (width * (MAX_BITMAP_DIMENSION.toDouble() / height)).toInt().coerceAtLeast(1)
                    height = MAX_BITMAP_DIMENSION
                }

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.density = android.util.DisplayMetrics.DENSITY_DEFAULT
                // Paint white first; PDFs render transparent "white" which looks wrong.
                Canvas(bitmap).drawColor(Color.WHITE)

                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
                return bitmap
            }
        }
    }

    /** Idempotent. Blocks only for an in-progress render, then releases native resources. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { pdfRenderer.close() } catch (_: Throwable) { }
            try { fileDescriptor.close() } catch (_: Throwable) { }
        }
    }

    companion object {
        private const val MAX_BITMAP_BYTES: Long = 72L * 1024L * 1024L // 72 MB
        private const val MAX_BITMAP_DIMENSION: Int = 8192
    }
}

/** Thrown by [RendererCore.renderPage] when the document is already closed. */
class RendererClosedException : IllegalStateException("PDF renderer is closed")
