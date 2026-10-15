package com.an.tetris

import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * 七种方块。base 是旋转状态 0 时，在 size×size 包围盒内 4 个格子的 (列, 行) 坐标，行向下为正。
 */
enum class PieceType(val color: Int, val size: Int, base: List<Pair<Int, Int>>) {
    I(0xFF22D3EE.toInt(), 4, listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)),
    O(0xFFFACC15.toInt(), 2, listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)),
    T(0xFFA855F7.toInt(), 3, listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1)),
    S(0xFF22C55E.toInt(), 3, listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1)),
    Z(0xFFEF4444.toInt(), 3, listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1)),
    J(0xFF3B82F6.toInt(), 3, listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1)),
    L(0xFFF97316.toInt(), 3, listOf(2 to 0, 0 to 1, 1 to 1, 2 to 1));

    /** rotations[r]：顺时针旋转 r 次后的 4 个格子 */
    val rotations: List<List<Pair<Int, Int>>> = run {
        val result = ArrayList<List<Pair<Int, Int>>>(4)
        var cur = base
        repeat(4) {
            result.add(cur)
            cur = cur.map { (x, y) -> (size - 1 - y) to x }
        }
        result
    }
}

/**
 * 纯逻辑的俄罗斯方块引擎，不依赖 Android，方便单元测试。
 * 由 View 每帧调用 [update] 驱动重力和锁定延迟。
 */
class TetrisGame(private val random: Random = Random.Default) {

    companion object {
        const val COLS = 10
        const val ROWS = 20
        const val PREVIEW_COUNT = 3
        const val LOCK_DELAY_MS = 500L
        const val MAX_LOCK_RESETS = 15
        private val LINE_SCORES = intArrayOf(0, 100, 300, 500, 800)

        /** 简化的踢墙表：旋转失败时依次尝试这些偏移 */
        private val KICKS = listOf(
            0 to 0, -1 to 0, 1 to 0, 0 to -1,
            -1 to -1, 1 to -1, -2 to 0, 2 to 0
        )
    }

    /** 0 = 空，其它值 = 方块颜色 */
    val board = Array(ROWS) { IntArray(COLS) }

    lateinit var current: PieceType
        private set
    var rotation = 0
        private set
    var x = 0
        private set
    var y = 0
        private set

    var hold: PieceType? = null
        private set
    var canHold = true
        private set

    var score = 0
        private set
    var lines = 0
        private set
    var level = 1
        private set

    var isGameOver = false
        private set
    var isPaused = false
        private set

    /** 消行回调（参数为本次消除行数），用于震动反馈 */
    var onLinesCleared: ((Int) -> Unit)? = null
    var onGameOver: (() -> Unit)? = null

    private val bag = ArrayList<PieceType>()
    private val queue = ArrayDeque<PieceType>()
    val next: List<PieceType> get() = queue.toList()

    private var gravityAcc = 0L
    private var lockTimer = 0L
    private var lockResets = 0

    private val active get() = !isGameOver && !isPaused

    init {
        reset()
    }

    fun reset() {
        board.forEach { it.fill(0) }
        bag.clear()
        queue.clear()
        hold = null
        canHold = true
        score = 0
        lines = 0
        level = 1
        isGameOver = false
        isPaused = false
        refillQueue()
        spawn(nextPiece())
    }

    fun togglePause() {
        if (!isGameOver) isPaused = !isPaused
    }

    fun pause() {
        if (!isGameOver) isPaused = true
    }

    // ---------- 生成 ----------

    /** 7-bag 随机：每 7 个方块中七种各出现一次 */
    private fun drawFromBag(): PieceType {
        if (bag.isEmpty()) bag.addAll(PieceType.entries.shuffled(random))
        return bag.removeAt(bag.lastIndex)
    }

    private fun refillQueue() {
        while (queue.size < PREVIEW_COUNT) queue.addLast(drawFromBag())
    }

    private fun nextPiece(): PieceType {
        val p = queue.removeFirst()
        refillQueue()
        return p
    }

    private fun spawn(type: PieceType) {
        current = type
        rotation = 0
        x = (COLS - type.size) / 2
        y = if (type == PieceType.I) -1 else 0
        gravityAcc = 0
        lockTimer = 0
        lockResets = 0
        if (!fits(type, rotation, x, y)) {
            isGameOver = true
            onGameOver?.invoke()
        }
    }

    /** 仅供测试：直接指定当前方块 */
    internal fun debugSpawn(type: PieceType) = spawn(type)

    // ---------- 碰撞 ----------

    fun fits(type: PieceType, rot: Int, px: Int, py: Int): Boolean {
        for ((cx, cy) in type.rotations[rot]) {
            val bx = px + cx
            val by = py + cy
            if (bx < 0 || bx >= COLS || by >= ROWS) return false
            if (by >= 0 && board[by][bx] != 0) return false
        }
        return true
    }

    private fun grounded() = !fits(current, rotation, x, y + 1)

    /** 落地后移动/旋转可重置锁定计时，但次数有限，防止无限拖延 */
    private fun onPieceMoved() {
        if (lockTimer > 0 && lockResets < MAX_LOCK_RESETS) {
            lockTimer = 0
            lockResets++
        }
    }

    // ---------- 操作 ----------

    fun moveLeft() = shift(-1)
    fun moveRight() = shift(1)

    private fun shift(dx: Int): Boolean {
        if (!active || !fits(current, rotation, x + dx, y)) return false
        x += dx
        onPieceMoved()
        return true
    }

    fun rotate(clockwise: Boolean = true): Boolean {
        if (!active || current == PieceType.O) return false
        val nr = (rotation + if (clockwise) 1 else 3) % 4
        for ((kx, ky) in KICKS) {
            if (fits(current, nr, x + kx, y + ky)) {
                rotation = nr
                x += kx
                y += ky
                onPieceMoved()
                return true
            }
        }
        return false
    }

    /** 软降一格（+1 分）；已经着地则立即锁定 */
    fun softDrop(): Boolean {
        if (!active) return false
        return if (!grounded()) {
            y++
            score += 1
            gravityAcc = 0
            true
        } else {
            lockPiece()
            false
        }
    }

    /** 硬降（每格 +2 分）并立即锁定 */
    fun hardDrop() {
        if (!active) return
        var d = 0
        while (!grounded()) {
            y++
            d++
        }
        score += d * 2
        lockPiece()
    }

    fun holdPiece(): Boolean {
        if (!active || !canHold) return false
        val prev = hold
        hold = current
        canHold = false
        spawn(prev ?: nextPiece())
        return true
    }

    fun ghostY(): Int {
        var gy = y
        while (fits(current, rotation, x, gy + 1)) gy++
        return gy
    }

    // ---------- 时间推进 ----------

    /** 当前等级下每下落一格的间隔（毫秒） */
    fun gravityInterval(): Long = max(50.0, 1000.0 * 0.85.pow(level - 1)).toLong()

    fun update(dtMs: Long) {
        if (!active) return
        val dt = dtMs.coerceIn(0, 100) // 防止卡顿后一次跳太多
        if (!grounded()) {
            lockTimer = 0
            gravityAcc += dt
            val interval = gravityInterval()
            while (gravityAcc >= interval && !grounded()) {
                gravityAcc -= interval
                y++
            }
            if (grounded()) gravityAcc = 0
        } else {
            gravityAcc = 0
            lockTimer += dt
            if (lockTimer >= LOCK_DELAY_MS) lockPiece()
        }
    }

    // ---------- 锁定与消行 ----------

    private fun lockPiece() {
        var outOfTop = false
        for ((cx, cy) in current.rotations[rotation]) {
            val bx = x + cx
            val by = y + cy
            if (by < 0) outOfTop = true else board[by][bx] = current.color
        }
        val cleared = clearLines()
        if (cleared > 0) {
            score += LINE_SCORES[cleared] * level
            lines += cleared
            level = lines / 10 + 1
            onLinesCleared?.invoke(cleared)
        }
        if (outOfTop) {
            isGameOver = true
            onGameOver?.invoke()
            return
        }
        canHold = true
        spawn(nextPiece())
    }

    private fun clearLines(): Int {
        var write = ROWS - 1
        var cleared = 0
        for (read in ROWS - 1 downTo 0) {
            if (board[read].all { it != 0 }) {
                cleared++
                continue
            }
            if (write != read) board[read].copyInto(board[write])
            write--
        }
        for (r in write downTo 0) board[r].fill(0)
        return cleared
    }
}
