package com.pdf.mylibrary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/**
 * Renders each PDF page on demand and caches the result.
 *
 * Concurrency contract (this is what fixes the PdfDocumentProxy / "Document already
 * closed" crashes):
 *  - Rendering is dispatched to a SINGLE background thread. PdfRenderer allows only one
 *    open page at a time, so one worker (plus RendererCore's own lock) guarantees pages
 *    are never opened concurrently.
 *  - Every in-flight render is tracked by position and cancelled when the row is
 *    recycled or re-bound, so we never keep rendering into a document that is about to
 *    be closed.
 *  - renderPage() throws RendererClosedException once the document is closed; the worker
 *    catches it and stops instead of touching freed native memory.
 *  - The bitmap cache is a thread-safe LruCache, bounded to a fraction of the heap.
 */
class PdfPageAdapter(
    private val context: Context,
    private val rendererCore: RendererCore,
    private val targetWidth: Int
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    /** LRU bitmap cache sized to ~1/8 of the heap (KB). LruCache is thread-safe. */
    private val bitmapCache: LruCache<Int, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheSizeKb = (maxKb / 8).coerceAtLeast(8 * 1024)
        object : LruCache<Int, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    /** In-flight render tasks, keyed by page position, so we can cancel them. */
    private val inFlight = ConcurrentHashMap<Int, Future<*>>()

    /** Single-thread pool: PdfRenderer is single-page, so one worker is correct. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PdfPageRenderer").apply { isDaemon = true }
    }

    @Volatile
    private var released = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ratioCache = ConcurrentHashMap<Int, Float>()
    private val placeholder = ColorDrawable(Color.parseColor("#F2F2F2"))

    class PageViewHolder(val imageView: PhotoView) : RecyclerView.ViewHolder(imageView) {
        /** Position currently bound; tracked because RecyclerView clears it on recycle. */
        var boundPosition: Int = RecyclerView.NO_POSITION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val imageView = PhotoView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        return PageViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // Cancel any render kicked off for this holder's previous binding.
        cancelRender(holder.boundPosition)
        holder.boundPosition = position

        // Pre-size the row so scrolling doesn't jump when the bitmap arrives.
        val ratio = ratioCache.getOrPut(position) {
            try { rendererCore.getPageAspectRatio(position) } catch (_: Throwable) { 1.4142f }
        }
        holder.imageView.layoutParams.height = (targetWidth * ratio).toInt().coerceAtLeast(1)
        holder.imageView.requestLayout()

        // Serve from cache immediately if present.
        bitmapCache.get(position)?.let { cached ->
            holder.imageView.setImageBitmap(cached)
            return
        }

        holder.imageView.setImageDrawable(placeholder)
        if (released) return

        val targetPosition = position
        val selfRef = arrayOfNulls<FutureTask<Unit>>(1)
        val task = FutureTask<Unit>({
            if (released || Thread.currentThread().isInterrupted) return@FutureTask
            val bmp = try {
                rendererCore.renderPage(targetPosition, targetWidth)
            } catch (_: Throwable) {
                // RendererClosedException or any transient failure: stop quietly.
                null
            }
            selfRef[0]?.let { inFlight.remove(targetPosition, it) }
            if (bmp != null && !released) {
                bitmapCache.put(targetPosition, bmp)
                mainHandler.post {
                    if (!released && holder.boundPosition == targetPosition) {
                        holder.imageView.setImageBitmap(bmp)
                    }
                }
            }
        }, Unit)
        selfRef[0] = task

        // Register before submitting so a fast worker can't finish before the put.
        inFlight.put(targetPosition, task)?.cancel(true)
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // Executor was shut down (release() raced us): drop the task.
            inFlight.remove(targetPosition, task)
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        cancelRender(holder.boundPosition)
        holder.boundPosition = RecyclerView.NO_POSITION
        holder.imageView.setImageDrawable(null)
    }

    override fun getItemCount(): Int = rendererCore.getPageCount()

    private fun cancelRender(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        inFlight.remove(position)?.cancel(true)
    }

    /** Stop all rendering and free resources. Safe to call multiple times. */
    fun release() {
        released = true
        for (future in inFlight.values) future.cancel(true)
        inFlight.clear()
        executor.shutdownNow()
        bitmapCache.evictAll()
        ratioCache.clear()
    }
}
