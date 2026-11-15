package com.local.ktv

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ListView

/**
 * 遥控器列表导航（P1 `TvListNavigation`）。
 *
 * 现状问题：清单页/歌星页只是普通 [ListView] + 可点击子项，焦点进入列表后方向键由
 * 平台默认规则乱跳（经常从第 3 列直接跳到页面底部按钮，或者第一行按 ↓ 就跳到分类条），
 * 现场用遥控器点歌很别扭。
 *
 * 这里改成显式导航：列表自己记住「行 + 行内序号」，
 * - ↓/↑ 换行、列位置保持，超过可视区自动 setSelection 滚一行；
 * - ←/→ 在行内左右移动（歌星网格一行 4 位）；
 * - 到边界返回 false，把事件交回平台，让焦点能正常跳到其它区域（分类条、按钮）。
 * 列表本身从不持有焦点，焦点永远落在具体格子/条目上，配合 [TvFocusStyler] 的红橙高亮。
 */
internal object TvListNavigation {
    private const val TAG = "TvListNav"

    /** 处理方向键；返回 true 表示已被列表消费。 */
    fun handle(list: ListView, code: Int): Boolean {
        val adapter = list.adapter ?: return false
        if (adapter.count <= 0) return false
        val focus = list.findFocus()
        val anchor = focusedAnchor(list) ?: return firstRow(list, code).also {
            AppLog.i(TAG, "enter key=$code noAnchor focus=${focus?.javaClass?.simpleName} inList=$it")
        }
        val row = anchor.first
        val column = anchor.second
        val handled = when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(list, row, 1, column)
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(list, row, -1, column)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveColumn(list, row, column, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveColumn(list, row, column, -1)
            else -> false
        }
        AppLog.i(TAG, "key=$code row=$row col=$column count=${adapter.count} handled=$handled")
        return handled
    }

    /** 焦点不在列表里时，第一次按方向键把焦点送进列表（方向决定落在首行还是末行）。 */
    private fun firstRow(list: ListView, code: Int): Boolean {
        if (code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_UP &&
            code != KeyEvent.KEYCODE_DPAD_RIGHT && code != KeyEvent.KEYCODE_DPAD_LEFT
        ) return false
        val first = list.firstVisiblePosition
        val last = (list.lastVisiblePosition).coerceAtMost(list.count - 1)
        val row = if (code == KeyEvent.KEYCODE_DPAD_UP) last else first
        return focusRow(list, row, 0)
    }

    private fun moveRow(list: ListView, row: Int, delta: Int, column: Int): Boolean {
        val target = row + delta
        if (target < 0 || target > list.count - 1) return false
        // 目标行还在可视区内就不用滚动；否则滚一行，保证焦点不会跑到屏幕外
        if (target < list.firstVisiblePosition) list.setSelection(target)
        if (target > list.lastVisiblePosition) list.setSelection(target - list.childCount + 1)
        return focusRow(list, target, column)
    }

    private fun moveColumn(list: ListView, row: Int, column: Int, delta: Int): Boolean {
        val rowView = rowView(list, row) ?: return focusRow(list, row, (column + delta).coerceAtLeast(0))
        val targets = focusables(rowView)
        val target = column + delta
        if (target < 0 || target >= targets.size) return false
        list.post { bestEffort(list, rowView, targets[target]) }
        return true
    }

    private fun focusRow(list: ListView, row: Int, column: Int): Boolean {
        val rowView = rowView(list, row)
        if (rowView != null) {
            val targets = focusables(rowView)
            if (targets.isNotEmpty()) {
                val clamped = column.coerceIn(0, targets.size - 1)
                list.post { bestEffort(list, rowView, targets[clamped]) }
                return true
            }
        }
        // 行还没被填充（刚 setSelection 之后）：等下一帧再取
        list.post {
            val view = rowView(list, row)
            view?.let { focusables(it).firstOrNull()?.requestFocus() }
        }
        return true
    }

    private fun bestEffort(list: ListView, rowView: View, target: View) {
        if (target.isAttachedToWindow && target.visibility == View.VISIBLE) {
            target.requestFocus()
        } else if (!rowView.hasFocus()) {
            focusables(rowView).firstOrNull()?.requestFocus()
        }
    }

    private fun rowView(list: ListView, position: Int): View? {
        val index = position - list.firstVisiblePosition
        if (index < 0 || index >= list.childCount) return null
        return list.getChildAt(index)
    }

    /** (行 position, 行内第几个可聚焦子项)。 */
    private fun focusedAnchor(list: ListView): Pair<Int, Int>? {
        val focused = list.findFocus() ?: return null
        if (focused === list) return null
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            if (!isInside(child, focused)) continue
            val column = focusables(child).indexOf(focused as? View)
            return (list.firstVisiblePosition + index) to (if (column >= 0) column else 0)
        }
        return null
    }

    private fun isInside(parent: View, child: View?): Boolean {
        if (child == null) return false
        var node: View? = child
        while (node != null) {
            if (node === parent) return true
            node = node.parent as? View
        }
        return false
    }

    private fun focusables(row: View): List<View> {
        val result = mutableListOf<View>()
        fun collect(view: View) {
            if (view is ViewGroup) {
                repeat(view.childCount) { collect(view.getChildAt(it)) }
            } else if (view.isClickable && view.isEnabled && view.visibility == View.VISIBLE) {
                result += view
            }
        }
        collect(row)
        return result
    }
}

/** 走 [TvListNavigation] 的 ListView。 */
class TvListView : ListView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: android.util.AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: android.util.AttributeSet?, defStyle: Int) :
        super(context, attrs, defStyle)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && TvListNavigation.handle(this, event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
