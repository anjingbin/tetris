package com.an.tetris

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.an.tetris.TetrisGame.Companion.COLS
import com.an.tetris.TetrisGame.Companion.ROWS
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * 游戏画面：棋盘 + 右侧信息栏 + 底部按键，全部用 Canvas 绘制。
 * 操作方式：
 *  - 按键：暂存 / 旋转 / 暂停，◀ ▼ ▶ 可长按连发，落底
 *  - 棋盘手势：点按=旋转，左右拖动=平移，下滑=落底，上滑=暂存
 *  - 实体键盘：方向键 / WASD，空格落底，C 暂存，Z 逆时针，P 暂停
 */
@SuppressLint("ViewConstructor")
class TetrisView(context: Context) : View(context) {

    private val game = TetrisGame()
    private val prefs = context.getSharedPreferences("tetris", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---------- 颜色 ----------
    private val colBoard = 0xFF1B1D23.toInt()
    private val colGrid = 0xFF262931.toInt()
    private val colButton = 0xFF262A33.toInt()
    private val colButtonPressed = 0xFF3D4350.toInt()
    private val colText = 0xFFE8EAF0.toInt()
    private val colMuted = 0xFF8A90A0.toInt()

    // ---------- 画笔 ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colMuted }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colText
        typeface = Typeface.DEFAULT_BOLD
    }
    private val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colText
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val tmp = RectF()

    // ---------- 布局 ----------
    private var cell = 0f
    private val boardRect = RectF()
    private var panelLeft = 0f
    private var panelWidth = 0f

    private class Btn(
        val label: () -> String,
        val repeat: Boolean,
        val action: () -> Unit
    ) {
        val rect = RectF()
        var pressedAt = 0L
        var lastFire = 0L
    }

    private val btnHold = Btn({ "暂存" }, false) { game.holdPiece() }
    private val btnRotate = Btn({ "旋转" }, false) { game.rotate() }
    private val btnPause = Btn({
        when {
            game.isGameOver -> "重开"
            game.isPaused -> "继续"
            else -> "暂停"
        }
    }, false) { if (game.isGameOver) game.reset() else game.togglePause() }
    private val btnLeft = Btn({ "◀" }, true) { game.moveLeft() }
    private val btnDown = Btn({ "▼" }, true) { game.softDrop() }
    private val btnRight = Btn({ "▶" }, true) { game.moveRight() }
    private val btnDrop = Btn({ "落底" }, false) { game.hardDrop() }

    private val row1 = listOf(btnHold, btnRotate, btnPause)
    private val row2 = listOf(btnLeft, btnDown, btnRight, btnDrop)
    private val buttons = row1 + row2

    // ---------- 触摸状态 ----------
    private val heldButtons = HashMap<Int, Btn>()
    private var boardPointer = -1
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var movedCols = 0
    private var dragged = false

    // ---------- 游戏循环 ----------
    private var running = false
    private var lastFrame = 0L
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            game.update(now - lastFrame)
            lastFrame = now
            processRepeats(now)
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        game.onLinesCleared = { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        game.onGameOver = { saveBest() }
    }

    fun resume() {
        if (running) return
        running = true
        lastFrame = SystemClock.uptimeMillis()
        requestFocus()
        postOnAnimation(frame)
    }

    /** 切到后台：暂停游戏、停止循环、保存最高分 */
    fun pauseForBackground() {
        game.pause()
        running = false
        removeCallbacks(frame)
        heldButtons.clear()
        boardPointer = -1
        saveBest()
        invalidate()
    }

    private fun saveBest() {
        if (game.score > best) {
            best = game.score
            prefs.edit().putInt("best", best).apply()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(frame)
    }

    // ---------- 布局计算 ----------

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        computeLayout()
    }

    private fun computeLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = dp(12f)
        val left = paddingLeft + pad
        val top = paddingTop + pad
        val right = w - paddingRight - pad
        val bottom = h - paddingBottom - pad

        val controlsH = ((bottom - top) * 0.24f).coerceIn(dp(120f), dp(190f))
        panelWidth = (right - left) * 0.27f
        val availW = right - left - panelWidth - pad
        val availH = bottom - top - controlsH - pad
        cell = floor(min(availW / COLS, availH / ROWS))

        val bw = cell * COLS
        val bh = cell * ROWS
        val totalW = bw + pad + panelWidth
        val bx = left + (right - left - totalW) / 2f
        boardRect.set(bx, top, bx + bw, top + bh)
        panelLeft = boardRect.right + pad

        // 按键：两行，贴底排列
        val gap = dp(10f)
        val areaTop = boardRect.bottom + pad
        val rowH = min((bottom - areaTop - gap) / 2f, dp(78f))
        val row2Top = bottom - rowH
        val row1Top = row2Top - gap - rowH
        layoutRow(row1, left, right, row1Top, rowH, gap)
        layoutRow(row2, left, right, row2Top, rowH, gap)
    }

    private fun layoutRow(row: List<Btn>, left: Float, right: Float, top: Float, h: Float, gap: Float) {
        val bw = (right - left - gap * (row.size - 1)) / row.size
        row.forEachIndexed { i, b ->
            val l = left + i * (bw + gap)
            b.rect.set(l, top, l + bw, top + h)
        }
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        if (cell <= 0f) return
        drawBoard(canvas)
        drawPanel(canvas)
        drawButtons(canvas)
        drawOverlay(canvas)
    }

    private fun drawBoard(canvas: Canvas) {
        fill.color = colBoard
        tmp.set(boardRect)
        tmp.inset(-dp(3f), -dp(3f))
        canvas.drawRoundRect(tmp, dp(8f), dp(8f), fill)

        stroke.color = colGrid
        stroke.strokeWidth = 1f
        for (c in 1 until COLS) {
            val x = boardRect.left + c * cell
            canvas.drawLine(x, boardRect.top, x, boardRect.bottom, stroke)
        }
        for (r in 1 until ROWS) {
            val y = boardRect.top + r * cell
            canvas.drawLine(boardRect.left, y, boardRect.right, y, stroke)
        }

        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val color = game.board[r][c]
            if (color != 0) drawCell(canvas, boardRect.left + c * cell, boardRect.top + r * cell, cell, color)
        }

        if (game.isGameOver) return
        val piece = game.current
        val cells = piece.rotations[game.rotation]

        // 幽灵块（落点预览）
        val gy = game.ghostY()
        stroke.color = piece.color
        stroke.alpha = 150
        stroke.strokeWidth = dp(1.5f)
        for ((cx, cy) in cells) {
            val r = gy + cy
            if (r < 0) continue
            val l = boardRect.left + (game.x + cx) * cell
            val t = boardRect.top + r * cell
            val inset = cell * 0.1f
            tmp.set(l + inset, t + inset, l + cell - inset, t + cell - inset)
            canvas.drawRoundRect(tmp, cell * 0.15f, cell * 0.15f, stroke)
        }
        stroke.alpha = 255

        for ((cx, cy) in cells) {
            val r = game.y + cy
            if (r < 0) continue
            drawCell(canvas, boardRect.left + (game.x + cx) * cell, boardRect.top + r * cell, cell, piece.color)
        }
    }

    private fun drawCell(canvas: Canvas, l: Float, t: Float, size: Float, color: Int, alpha: Int = 255) {
        val inset = size * 0.06f
        tmp.set(l + inset, t + inset, l + size - inset, t + size - inset)
        fill.color = color
        fill.alpha = alpha
        canvas.drawRoundRect(tmp, size * 0.18f, size * 0.18f, fill)
        // 顶部高光
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = alpha * 45 / 255
        tmp.set(l + inset * 2, t + inset * 2, l + size - inset * 2, t + size * 0.32f)
        canvas.drawRoundRect(tmp, size * 0.1f, size * 0.1f, fill)
        fill.alpha = 255
    }

    private fun drawPieceInBox(canvas: Canvas, type: PieceType, box: RectF, alpha: Int = 255) {
        val cells = type.rotations[0]
        val minX = cells.minOf { it.first }
        val maxX = cells.maxOf { it.first }
        val minY = cells.minOf { it.second }
        val maxY = cells.maxOf { it.second }
        val pw = maxX - minX + 1
        val ph = maxY - minY + 1
        val c = min(cell * 0.8f, min(box.width() / (pw + 1), box.height() / (ph + 1)))
        val ox = box.centerX() - pw * c / 2f - minX * c
        val oy = box.centerY() - ph * c / 2f - minY * c
        for ((cx, cy) in cells) drawCell(canvas, ox + cx * c, oy + cy * c, c, type.color, alpha)
    }

    private fun drawPanel(canvas: Canvas) {
        val pw = panelWidth
        val labelSize = min(pw * 0.14f, dp(14f))
        val valueSize = min(pw * 0.2f, dp(22f))
        labelPaint.textSize = labelSize
        valuePaint.textSize = valueSize
        val bh = boardRect.height()
        var y = boardRect.top

        // 暂存
        y += labelSize
        canvas.drawText("暂存", panelLeft, y, labelPaint)
        y += dp(6f)
        val holdH = min(pw * 0.7f, bh * 0.14f)
        tmp.set(panelLeft, y, panelLeft + pw, y + holdH)
        fill.color = colBoard
        canvas.drawRoundRect(tmp, dp(8f), dp(8f), fill)
        val holdBox = RectF(tmp)
        game.hold?.let { drawPieceInBox(canvas, it, holdBox, if (game.canHold) 255 else 90) }
        y += holdH + dp(14f)

        // 下一个
        y += labelSize
        canvas.drawText("下一个", panelLeft, y, labelPaint)
        y += dp(6f)
        val slotH = min(pw * 0.55f, bh * 0.11f)
        val nextList = game.next
        tmp.set(panelLeft, y, panelLeft + pw, y + slotH * nextList.size)
        fill.color = colBoard
        canvas.drawRoundRect(tmp, dp(8f), dp(8f), fill)
        nextList.forEachIndexed { i, type ->
            val box = RectF(panelLeft, y + i * slotH, panelLeft + pw, y + (i + 1) * slotH)
            drawPieceInBox(canvas, type, box, if (i == 0) 255 else 170)
        }
        y += slotH * nextList.size + dp(16f)

        // 分数信息
        val stats = listOf(
            "分数" to game.score.toString(),
            "行数" to game.lines.toString(),
            "等级" to game.level.toString(),
            "最高" to maxOf(best, game.score).toString()
        )
        val step = labelSize + valueSize + dp(10f)
        for ((label, value) in stats) {
            if (y + step > boardRect.bottom + dp(4f)) break
            y += labelSize
            canvas.drawText(label, panelLeft, y, labelPaint)
            y += valueSize + dp(2f)
            canvas.drawText(value, panelLeft, y, valuePaint)
            y += dp(8f)
        }
    }

    private fun drawButtons(canvas: Canvas) {
        for (b in buttons) {
            val pressed = heldButtons.containsValue(b)
            fill.color = if (pressed) colButtonPressed else colButton
            canvas.drawRoundRect(b.rect, dp(14f), dp(14f), fill)
            centerText.textSize = min(b.rect.height() * 0.36f, dp(22f))
            centerText.color = colText
            val ty = b.rect.centerY() - (centerText.ascent() + centerText.descent()) / 2f
            canvas.drawText(b.label(), b.rect.centerX(), ty, centerText)
        }
    }

    private fun drawOverlay(canvas: Canvas) {
        if (!game.isPaused && !game.isGameOver) return
        fill.color = 0xCC0B0C0F.toInt()
        canvas.drawRect(boardRect, fill)
        val cx = boardRect.centerX()
        val cy = boardRect.centerY()
        centerText.color = colText
        centerText.textSize = cell * 1.3f
        canvas.drawText(if (game.isGameOver) "游戏结束" else "已暂停", cx, cy - cell * 0.6f, centerText)
        centerText.textSize = cell * 0.65f
        centerText.color = colMuted
        if (game.isGameOver) {
            canvas.drawText("得分 ${game.score}", cx, cy + cell * 0.8f, centerText)
            canvas.drawText("点击棋盘重新开始", cx, cy + cell * 2f, centerText)
        } else {
            canvas.drawText("点击棋盘继续", cx, cy + cell * 0.9f, centerText)
        }
    }

    // ---------- 触摸 ----------

    private fun processRepeats(now: Long) {
        for (b in heldButtons.values) {
            if (b.repeat && now - b.pressedAt >= 170 && now - b.lastFire >= 50) {
                b.action()
                b.lastFire = now
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val px = e.getX(i)
                val py = e.getY(i)
                val btn = buttons.firstOrNull { it.rect.contains(px, py) }
                if (btn != null) {
                    val now = SystemClock.uptimeMillis()
                    btn.pressedAt = now
                    btn.lastFire = now
                    heldButtons[id] = btn
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    btn.action()
                } else if (boardPointer == -1 && boardRect.contains(px, py)) {
                    boardPointer = id
                    startX = px
                    startY = py
                    startTime = e.eventTime
                    movedCols = 0
                    dragged = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val i = if (boardPointer == -1) -1 else e.findPointerIndex(boardPointer)
                if (i >= 0) {
                    val dx = e.getX(i) - startX
                    val dy = e.getY(i) - startY
                    if (abs(dx) > cell * 0.5f || abs(dy) > cell * 0.5f) dragged = true
                    // 只有水平方向占主导时才平移，避免下滑时左右抖动
                    if (abs(dx) > abs(dy) * 0.8f) {
                        val target = (dx / cell).toInt()
                        while (movedCols < target) { game.moveRight(); movedCols++ }
                        while (movedCols > target) { game.moveLeft(); movedCols-- }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                heldButtons.remove(id)
                if (id == boardPointer) {
                    onBoardGestureEnd(e.getX(i) - startX, e.getY(i) - startY, e.eventTime - startTime)
                    boardPointer = -1
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                heldButtons.clear()
                boardPointer = -1
            }
        }
        return true
    }

    private fun onBoardGestureEnd(dx: Float, dy: Float, dt: Long) {
        when {
            game.isGameOver -> game.reset()
            game.isPaused -> game.togglePause()
            !dragged && dt < 300 -> game.rotate()
            dy > cell * 2 && abs(dy) > abs(dx) * 1.5f -> game.hardDrop()
            dy < -cell * 2 && abs(dy) > abs(dx) * 1.5f -> game.holdPiece()
        }
    }

    // ---------- 实体键盘（模拟器调试方便） ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> game.moveLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> game.moveRight()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> game.softDrop()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_X -> game.rotate()
            KeyEvent.KEYCODE_Z -> game.rotate(clockwise = false)
            KeyEvent.KEYCODE_SPACE -> game.hardDrop()
            KeyEvent.KEYCODE_C -> game.holdPiece()
            KeyEvent.KEYCODE_P -> game.togglePause()
            KeyEvent.KEYCODE_ENTER -> if (game.isGameOver) game.reset() else game.togglePause()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
