package com.an.tetris

import com.an.tetris.TetrisGame.Companion.COLS
import com.an.tetris.TetrisGame.Companion.ROWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TetrisGameTest {

    @Test
    fun fourRotationsReturnToStart() {
        for (p in PieceType.entries) {
            assertEquals(4, p.rotations.size)
            p.rotations.forEach { assertEquals(4, it.size) }
            // 第 4 次顺时针旋转应回到初始形状
            val fifth = p.rotations[3].map { (x, y) -> (p.size - 1 - y) to x }
            assertEquals(p.rotations[0].toSet(), fifth.toSet())
            // 所有旋转都在包围盒内
            p.rotations.flatten().forEach { (x, y) ->
                assertTrue(x in 0 until p.size && y in 0 until p.size)
            }
        }
    }

    @Test
    fun sevenBagGivesAllSevenPieces() {
        val g = TetrisGame(Random(42))
        val seen = mutableListOf<PieceType>()
        repeat(7) {
            seen += g.current
            g.hardDrop()
        }
        assertEquals(7, seen.toSet().size)
    }

    @Test
    fun hardDropIClearsBottomLine() {
        val g = TetrisGame(Random(1))
        for (c in 0 until COLS) if (c !in 3..6) g.board[ROWS - 1][c] = 1
        g.debugSpawn(PieceType.I)
        g.hardDrop()
        assertEquals(1, g.lines)
        // I 从 y=-1 落到 y=18，共 19 格 × 2 分 + 单行 100 分
        assertEquals(19 * 2 + 100, g.score)
        assertTrue(g.board[ROWS - 1].all { it == 0 })
    }

    @Test
    fun tetrisScores800() {
        val g = TetrisGame(Random(1))
        for (r in ROWS - 4 until ROWS) for (c in 0 until COLS) if (c != 0) g.board[r][c] = 1
        g.debugSpawn(PieceType.I)
        assertTrue(g.rotate())          // 竖起来
        repeat(5) { g.moveLeft() }      // 推到最左
        val before = g.score
        g.hardDrop()
        assertEquals(4, g.lines)
        assertTrue(g.score - before >= 800)
        assertTrue(g.board.all { row -> row.all { it == 0 } })
    }

    @Test
    fun wallsBlockMovement() {
        val g = TetrisGame(Random(3))
        g.debugSpawn(PieceType.O)
        repeat(20) { g.moveLeft() }
        assertFalse(g.moveLeft())
        assertEquals(0, g.x)
        repeat(20) { g.moveRight() }
        assertFalse(g.moveRight())
        assertEquals(COLS - 2, g.x)
    }

    @Test
    fun holdOnlyOncePerPiece() {
        val g = TetrisGame(Random(7))
        val first = g.current
        assertTrue(g.holdPiece())
        assertEquals(first, g.hold)
        assertFalse(g.holdPiece())
        g.hardDrop()
        assertTrue(g.holdPiece())
    }

    @Test
    fun gravityAndLockDelay() {
        val g = TetrisGame(Random(5))
        g.debugSpawn(PieceType.T)
        val y0 = g.y
        repeat(10) { g.update(100) } // 1 秒，等级 1 下落一格
        assertEquals(y0 + 1, g.y)
        // 一直推进直到锁定并生成新方块
        var guard = 0
        while (g.board[ROWS - 1].all { it == 0 } && guard++ < 1000) g.update(100)
        assertTrue(g.board[ROWS - 1].any { it != 0 })
    }

    @Test
    fun spawnBlockedMeansGameOver() {
        val g = TetrisGame(Random(9))
        for (r in 0 until ROWS) g.board[r][4] = 1
        g.debugSpawn(PieceType.T)
        assertTrue(g.isGameOver)
    }
}
