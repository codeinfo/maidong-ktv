package com.local.ktv

/**
 * 搜索词归一化（P1）。
 *
 * i歌霸曲库的歌名/歌手是简体、半角，而现场输入常见三种噪声：
 * - 全角字符（搜「ＬＩＵ」或全角逗号）——SQLite 的 LIKE 按字节比，差一个全角就搜不到；
 * - 中间夹空格（「周 杰 伦」「刘德华 忘情水」）——整串当 pattern 会一个都命中不了；
 * - 大小写/多余空白。
 *
 * 这里把输入拆成词、统一成半角、去掉标点，再交给 SQL 按「词级 AND」匹配。
 */
internal object SongSearchTokens {
    /** 全角 ASCII 区 + 全角空格 → 半角。 */
    private fun halfWidth(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\u3000' -> builder.append(' ')
                ch in '\uFF01'..'\uFF5E' -> builder.append((ch.code - 0xFEE0).toChar())
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /** 去掉首尾与重复空白，丢弃纯标点词。 */
    fun of(query: String): List<String> =
        halfWidth(query).trim()
            .split(' ', '\t', '\n', ',', '，', '、', '·', '|')
            .map { it.trim() }
            .filter { token -> token.isNotEmpty() && token.any { it.isLetterOrDigit() } }
            .take(4)
}
