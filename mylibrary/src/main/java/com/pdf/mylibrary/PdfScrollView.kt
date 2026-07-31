package com.pdf.mylibrary

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class PdfScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var rendererCore: RendererCore? = null
    private var adapter: PdfPageAdapter? = null

    private var scaleFactor = 1f

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRerender: Runnable? = null
    private val rerenderDelayMs = 120L

    private val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1f, 5f)
                if (prev != scaleFactor) scheduleRerenderVisiblePages()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                parent?.requestDisallowInterceptTouchEvent(false)
                scheduleRerenderVisiblePages(immediate = true)
            }
        })

    init {
        addView(recyclerView)
        recyclerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false
        }
    }

    private fun scheduleRerenderVisiblePages(immediate: Boolean = false) {
        pendingRerender?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { rerenderVisiblePages() }
        pendingRerender = runnable
        if (immediate) handler.post(runnable) else handler.postDelayed(runnable, rerenderDelayMs)
    }

    private fun rerenderVisiblePages() {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
        if (first <= last) {
            adapter?.let {
                it.notifyItemRangeChanged(first, last - first + 1)
            }
        }
    }

    fun fromFile(file: File) {
        close()
        val parcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        rendererCore = RendererCore(parcelFileDescriptor)
        setupAdapter()
    }

    fun fromUri(uri: Uri) {
        val file = File(context.cacheDir, "temp.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        fromFile(file)
    }

    fun fromBytes(bytes: ByteArray) {
        val file = File(context.cacheDir, "temp.pdf")
        file.writeBytes(bytes)
        fromFile(file)
    }

    fun fromAsset(assetName: String) {
        val file = File(context.cacheDir, "asset.pdf")
        context.assets.open(assetName).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        fromFile(file)
    }

    private fun setupAdapter() {
        rendererCore?.let { core ->
            val screenWidth = resources.displayMetrics.widthPixels
            adapter = PdfPageAdapter(context, core, screenWidth)
            recyclerView.adapter = adapter
        }
    }

    fun close() {
        pendingRerender?.let { handler.removeCallbacks(it) }
        pendingRerender = null
        // Order matters: stop/await the render worker BEFORE closing the document,
        // otherwise a background render can hit a freed PdfRenderer and crash with
        // "PdfDocumentProxy cannot be null" / "Document already closed".
        adapter?.release()
        adapter = null
        recyclerView.adapter = null
        rendererCore?.close()
        rendererCore = null
        scaleFactor = 1f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Only drop the pending re-render callback here. We deliberately do NOT close
        // the document on detach: the view may reattach (e.g. rotation) and callers
        // own the document lifecycle via close(). Background renders are safe to let
        // finish because RendererCore serializes them and close() is ordered.
        pendingRerender?.let { handler.removeCallbacks(it) }
        pendingRerender = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}
