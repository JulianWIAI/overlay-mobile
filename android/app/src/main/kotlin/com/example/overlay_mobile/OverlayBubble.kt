package com.example.overlay_mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT  as MATCH
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT  as WRAP
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating Action Bubble: a draggable circle that sits above the vision-filter overlay
 * and provides a quick-access panel for switching vision modes and toggling secondary
 * post-process effects.
 *
 * Two WindowManager windows are managed:
 *
 *   Bubble window  — WRAP_CONTENT circle, FLAG_NOT_FOCUSABLE (touch-interactive).
 *                    Draggable anywhere on screen; snaps to the nearest vertical edge
 *                    with a spring-out animation on release.
 *
 *   Panel window   — Fixed-width card, FLAG_NOT_FOCUSABLE | FLAG_WATCH_OUTSIDE_TOUCH.
 *                    Appears beside the bubble on tap; fades in/out with a scale
 *                    animation originating from the bubble-side pivot.
 *                    Receives ACTION_OUTSIDE to auto-dismiss on external tap.
 *
 * Panel contents:
 *   • Mode grid   — 4-column grid of all 18 vision modes; tapping applies the mode
 *                   via [PixelProcessor.setTransform] and highlights the active cell.
 *
 * All WindowManager operations (add/remove/update) are called on the main thread.
 * [PixelProcessor] writes are volatile and safe from any thread.
 */
class OverlayBubble(private val context: Context) {

    // ── Constants ──────────────────────────────────────────────────────────────

    private companion object {
        const val BUBBLE_DP   = 56
        const val PANEL_W_DP  = 256
        const val SNAP_GAP_DP = 10
        const val CELL_H_DP   = 38
    }

    // ── Screen + device config ─────────────────────────────────────────────────

    private val dm       = context.resources.displayMetrics
    private val wm       = context.getSystemService(WindowManager::class.java)
    private val tapSlop  = ViewConfiguration.get(context).scaledTouchSlop

    private val screenW  : Int
    private val screenH  : Int
    private val bubblePx : Int
    private val panelWPx : Int
    private val snapGapPx: Int

    // ── Windows ────────────────────────────────────────────────────────────────

    private var bubbleView  : BubbleView?                 = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelView   : View?                       = null
    private var panelParams : WindowManager.LayoutParams? = null
    private var isPanelOpen                               = false

    // ── Live state ─────────────────────────────────────────────────────────────

    private var activeModeId = TransformCatalog.NORMAL
    private val modeCells    = HashMap<Int, View>(24)

    init {
        val bounds = wm.currentWindowMetrics.bounds
        screenW    = bounds.width()
        screenH    = bounds.height()
        bubblePx   = dp(BUBBLE_DP)
        panelWPx   = dp(PANEL_W_DP)
        snapGapPx  = dp(SNAP_GAP_DP)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Must be called on the main thread. */
    fun show() {
        activeModeId = TransformCatalog.idOf(PixelProcessor.activeTransform)
        addBubble()
    }

    /** Must be called on the main thread. */
    fun hide() {
        dismissPanel(animate = false)
        removeBubble()
    }

    // ── Bubble window ──────────────────────────────────────────────────────────

    private fun addBubble() {
        val view   = BubbleView(context)
        val params = overlayParams(bubblePx, bubblePx, WF.FLAG_NOT_FOCUSABLE or WF.FLAG_NOT_TOUCH_MODAL or WF.FLAG_LAYOUT_IN_SCREEN).apply {
            gravity = Gravity.TOP or Gravity.START
            x       = screenW - bubblePx - snapGapPx
            y       = screenH / 3
        }
        view.setOnTouchListener(makeDragTapListener(view))
        bubbleView   = view
        bubbleParams = params
        wm.addView(view, params)
    }

    private fun removeBubble() {
        bubbleView?.let { safeRemoveView(it) }
        bubbleView   = null
        bubbleParams = null
    }

    // ── Drag + tap gesture ─────────────────────────────────────────────────────

    private fun makeDragTapListener(bubbleRef: View): View.OnTouchListener {
        var rawDownX = 0f
        var rawDownY = 0f
        var dragging  = false

        return View.OnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    rawDownX = ev.rawX
                    rawDownY = ev.rawY
                    dragging  = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val p  = bubbleParams ?: return@OnTouchListener true
                    val dx = ev.rawX - rawDownX
                    val dy = ev.rawY - rawDownY
                    if (!dragging && (abs(dx) > tapSlop || abs(dy) > tapSlop)) {
                        dragging = true
                        dismissPanel(animate = true)
                    }
                    if (dragging) {
                        p.x       = (p.x + dx.toInt()).coerceIn(0, screenW - bubblePx)
                        p.y       = (p.y + dy.toInt()).coerceIn(0, screenH - bubblePx)
                        safeUpdateView(bubbleRef, p)
                        rawDownX  = ev.rawX
                        rawDownY  = ev.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) snapToEdge() else togglePanel()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge()
                }
            }
            true
        }
    }

    private fun snapToEdge() {
        val p    = bubbleParams ?: return
        val view = bubbleView   ?: return
        val targetX = if (p.x + bubblePx / 2 < screenW / 2) snapGapPx
                      else screenW - bubblePx - snapGapPx
        ValueAnimator.ofInt(p.x, targetX).apply {
            duration     = 270L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { a ->
                p.x = a.animatedValue as Int
                safeUpdateView(view, p)
            }
            start()
        }
    }

    // ── Panel window ───────────────────────────────────────────────────────────

    private fun togglePanel() {
        if (isPanelOpen) dismissPanel(animate = true) else presentPanel()
    }

    private fun presentPanel() {
        if (isPanelOpen) return
        activeModeId = TransformCatalog.idOf(PixelProcessor.activeTransform)

        val p       = bubbleParams ?: return
        val onRight = p.x + bubblePx / 2 > screenW / 2

        val panelX  = if (onRight) (p.x - panelWPx - dp(6)).coerceAtLeast(0)
                      else         (p.x + bubblePx + dp(6)).coerceAtMost(screenW - panelWPx)
        val panelY  = p.y.coerceIn(0, (screenH - dp(440)).coerceAtLeast(0))

        val params  = overlayParams(
            panelWPx, WF.WRAP_CONTENT,
            WF.FLAG_NOT_FOCUSABLE or WF.FLAG_NOT_TOUCH_MODAL or WF.FLAG_LAYOUT_IN_SCREEN or WF.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelX; y = panelY
        }

        val view   = buildPanelView()
        view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { dismissPanel(animate = true); true } else false
        }

        // Scale-in from the bubble-adjacent corner
        val pivX   = if (onRight) panelWPx.toFloat() else 0f
        view.pivotX = pivX;  view.pivotY = 0f
        view.alpha  = 0f;    view.scaleX = 0.87f; view.scaleY = 0.87f

        panelView   = view
        panelParams = params
        wm.addView(view, params)
        isPanelOpen = true
        bubbleView?.invalidate()

        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun dismissPanel(animate: Boolean) {
        if (!isPanelOpen) return
        isPanelOpen = false
        val dying   = panelView ?: return
        panelView   = null
        panelParams = null
        modeCells.clear()
        bubbleView?.invalidate()

        if (animate) {
            dying.animate().alpha(0f).scaleX(0.87f).scaleY(0.87f)
                .setDuration(130).setInterpolator(AccelerateInterpolator())
                .withEndAction { safeRemoveView(dying) }
                .start()
        } else {
            safeRemoveView(dying)
        }
    }

    // ── Panel view construction ────────────────────────────────────────────────

    private fun buildPanelView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background  = roundRect(Color.argb(238, 13, 13, 22), dp(18).toFloat())
            elevation   = dp(12).toFloat()
        }

        root.addView(buildHeader(),      linLp(MATCH, dp(38)).also { it.bottomMargin = dp(4) })
        root.addView(buildModeSection(), linLp(MATCH, dp(220)))

        return root
    }

    private fun buildHeader(): View {
        val frame = FrameLayout(context)

        frame.addView(
            label("VISION MODE", 11f, Color.WHITE, bold = true, spacing = 0.12f),
            flLp(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START),
        )
        frame.addView(
            label("✕", 15f, Color.argb(170, 255, 255, 255)).also { closeBtn ->
                closeBtn.setPadding(dp(6), dp(4), dp(6), dp(4))
                closeBtn.setOnClickListener { dismissPanel(animate = true) }
            },
            flLp(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END),
        )
        return frame
    }

    private fun buildModeSection(): View {
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }

        val allModes = listOf(
            TransformCatalog.NORMAL       to "Normal",
            TransformCatalog.DOG          to "Dog",
            TransformCatalog.CAT          to "Cat",
            TransformCatalog.BULL         to "Bull",
            TransformCatalog.BEE          to "Bee",
            TransformCatalog.FROG         to "Frog",
            TransformCatalog.EAGLE        to "Eagle",
            TransformCatalog.PROTANOPIA   to "Protan",
            TransformCatalog.DEUTERANOPIA to "Deutan",
            TransformCatalog.TRITANOPIA   to "Tritan",
            TransformCatalog.MONOCHROME   to "Mono",
            TransformCatalog.INVERTED     to "Invert",
            TransformCatalog.THERMAL      to "Thermal",
            TransformCatalog.NIGHT_VISION to "Night",
            TransformCatalog.ECHOLOCATION to "Echo",
            TransformCatalog.INFRARED     to "IR",
            TransformCatalog.ULTRAVIOLET  to "UV",
            TransformCatalog.HIGH_CONTRAST to "Hi-Con",
        )

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val gap  = dp(3)

        allModes.chunked(4).forEach { row ->
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { idx, (modeId, name) ->
                val cell = buildModeCell(modeId, name, modeId == activeModeId)
                modeCells[modeId] = cell
                rowView.addView(cell, linLp(0, dp(CELL_H_DP), 1f).also {
                    if (idx < row.lastIndex) it.marginEnd = gap
                })
            }
            repeat(4 - row.size) {
                rowView.addView(Space(context), linLp(0, dp(CELL_H_DP), 1f))
            }
            grid.addView(rowView, linLp(MATCH, WRAP).also { it.bottomMargin = gap })
        }

        scroll.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
        return scroll
    }

    private fun buildModeCell(modeId: Int, name: String, active: Boolean): View {
        val cell = FrameLayout(context).apply {
            background = cellBg(active)
            setOnClickListener { onModeSelected(modeId) }
        }
        cell.addView(
            label(name, 9.5f, if (active) Color.WHITE else Color.argb(190, 210, 210, 240),
                  ellipsize = TextUtils.TruncateAt.END),
            flLp(MATCH, WRAP, Gravity.CENTER),
        )
        return cell
    }


    // ── Mode / effect callbacks ────────────────────────────────────────────────

    private fun onModeSelected(modeId: Int) {
        val transform = TransformCatalog.forId(modeId) ?: return
        PixelProcessor.setTransform(transform)

        val prev   = activeModeId
        activeModeId = modeId

        modeCells[prev]?.let   { refreshCell(it, false, cellBg(false), Color.argb(190, 210, 210, 240)) }
        modeCells[modeId]?.let { refreshCell(it, true,  cellBg(true),  Color.WHITE) }
    }

    private fun refreshCell(cell: View, active: Boolean, bg: GradientDrawable, textColor: Int) {
        cell.background = bg
        ((cell as? FrameLayout)?.getChildAt(0) as? TextView)?.setTextColor(textColor)
    }

    // ── Inner bubble view ──────────────────────────────────────────────────────

    private inner class BubbleView(ctx: Context) : View(ctx) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(195, 16, 16, 26)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.argb(200, 65, 130, 255)
            strokeWidth = dp(2.5f).toFloat()
            style       = Paint.Style.STROKE
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.WHITE
            textSize  = dp(22).toFloat()
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width  / 2f
            val cy = height / 2f
            val r  = cx - dp(2)
            canvas.drawCircle(cx, cy, r, bgPaint)
            if (isPanelOpen) canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth / 2, ringPaint)
            // Eye glyph — centred vertically accounting for text descent
            canvas.drawText("👁", cx, cy + iconPaint.textSize * 0.36f, iconPaint)
        }
    }

    // ── Drawing / layout helpers ───────────────────────────────────────────────

    private fun cellBg(active: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat()
        setColor(if (active) Color.argb(255, 52, 108, 255) else Color.argb(48, 195, 195, 255))
    }

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun label(
        text:      String,
        sizeSp:    Float,
        color:     Int,
        bold:      Boolean                  = false,
        spacing:   Float                    = 0f,
        ellipsize: TextUtils.TruncateAt?    = null,
    ) = TextView(context).apply {
        this.text      = text
        textSize       = sizeSp
        setTextColor(color)
        gravity        = android.view.Gravity.CENTER
        maxLines       = 1
        if (bold)      typeface  = android.graphics.Typeface.DEFAULT_BOLD
        if (spacing != 0f) letterSpacing = spacing
        if (ellipsize != null) this.ellipsize = ellipsize
    }

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags, PixelFormat.TRANSLUCENT,
    )

    private fun linLp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun flLp(w: Int, h: Int, gravity: Int)        = FrameLayout.LayoutParams(w, h, gravity)

    private fun dp(v: Int)   = (v * dm.density + 0.5f).toInt()
    private fun dp(v: Float) = (v * dm.density + 0.5f).toInt()

    private fun safeRemoveView(v: View) {
        try { wm.removeView(v) } catch (_: IllegalArgumentException) { }
    }

    private fun safeUpdateView(v: View, p: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, p) } catch (_: IllegalArgumentException) { }
    }
}

private typealias WF = WindowManager.LayoutParams
