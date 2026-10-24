package io.github.korenkrita.simulsub

/**
 * The last hand a translation passes through before it reaches the screen.
 *
 * Live captions are translated from recognition output that is sometimes wrong, and a model asked to translate
 * garbage answers with fluent nonsense instead of saying so (a fragment "るがきま。" became "你别急嘛。"). The
 * model cannot be trusted to refuse, so what it returns is cleaned here — a deterministic, testable step that
 * never invents anything:
 *
 * - an empty answer falls back to the original text (a caption is never blank),
 * - stray kana inside the Chinese answer is dropped (it is noise, not content),
 * - an English filler left untranslated ("我觉得 maybe 我还能行") is dropped, while names are kept,
 * - traditional characters are converted to simplified (the target is 简体),
 * - spacing and separators left by those removals are tidied.
 *
 * [suspectSource] is the other half: when the recognition mixed scripts (an English sentence transcribed into
 * kana), the translation cannot be repaired afterwards, so callers may show the original instead.
 */
object TranslationPost {
    /** English words that carry no meaning once translated: a leftover, not a name. */
    private val FILLERS = setOf(
        "a", "an", "and", "but", "so", "or", "the", "this", "that", "it", "its", "i", "im", "you", "we",
        "they", "he", "she", "like", "maybe", "well", "oh", "ah", "yeah", "yes", "no", "ok", "okay",
        "really", "very", "just", "know", "think", "mean", "what", "why", "how",
    )

    private val KANA = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]+")
    private val LATIN_WORD = Regex("[A-Za-z][A-Za-z']*")

    /** Traditional characters that show up in subtitles, with their simplified form. */
    private val SIMPLIFIED = mapOf(
        '這' to '这', '個' to '个', '們' to '们', '說' to '说', '時' to '时', '會' to '会', '來' to '来',
        '沒' to '没', '學' to '学', '過' to '过', '點' to '点', '麼' to '么', '為' to '为', '對' to '对',
        '開' to '开', '關' to '关', '現' to '现', '實' to '实', '應' to '应', '覺' to '觉', '讓' to '让',
        '邊' to '边', '還' to '还', '進' to '进', '愛' to '爱', '買' to '买', '賣' to '卖', '錢' to '钱',
        '電' to '电', '話' to '话', '語' to '语', '讀' to '读', '寫' to '写', '聽' to '听', '見' to '见',
        '問' to '问', '題' to '题', '樣' to '样', '種' to '种', '給' to '给', '帶' to '带', '幫' to '帮',
        '藝' to '艺', '術' to '术', '動' to '动', '畫' to '画', '遊' to '游', '戲' to '戏', '歡' to '欢',
        '樂' to '乐', '聲' to '声', '請' to '请', '謝' to '谢', '認' to '认', '識' to '识', '錯' to '错',
        '決' to '决', '準' to '准', '備' to '备', '經' to '经', '歷' to '历', '國' to '国', '際' to '际',
        '網' to '网', '線' to '线', '體' to '体', '頭' to '头', '臉' to '脸', '發' to '发', '幾' to '几',
        '離' to '离', '難' to '难', '厲' to '厉', '嗎' to '吗', '雖' to '虽', '願' to '愿', '張' to '张',
        '長' to '长', '門' to '门', '間' to '间', '陽' to '阳', '陰' to '阴', '雲' to '云', '風' to '风',
        '鳥' to '鸟', '魚' to '鱼', '馬' to '马', '龍' to '龙', '貓' to '猫', '紅' to '红', '綠' to '绿',
        '藍' to '蓝', '黃' to '黄', '歲' to '岁', '萬' to '万', '與' to '与', '於' to '于', '後' to '后',
        '裡' to '里', '兩' to '两', '單' to '单', '雙' to '双', '業' to '业', '總' to '总', '統' to '统',
        '級' to '级', '團' to '团', '隊' to '队', '戰' to '战', '鬥' to '斗', '勝' to '胜', '負' to '负',
    )

    /**
     * The caption to show for [zh], translated from [ja]. Never blank: a blank answer shows [ja] instead,
     * because the original text is at least true.
     */
    fun tidy(zh: String, ja: String): String {
        val source = ja.trim()
        var s = zh.trim()
        if (s.isEmpty()) return source
        s = KANA.replace(s, "")
        s = dropFillers(s)
        // a removed filler leaves its separator and the spaces around it: "我觉得，maybe 我还能行"
        s = s.replace(Regex("，\\s+"), "")
        s = s.replace(Regex("\\s+"), " ")
        // Chinese needs no space between characters, so a space between two of them is a leftover
        s = s.replace(Regex("(?<=[\\u4E00-\\u9FFF])\\s+(?=[\\u4E00-\\u9FFF])"), "")
        s = convert(s)
        val hadStop = s.endsWith("。")
        s = s.trim('，', ',', '、', ' ', '。')
        if (hadStop && s.isNotEmpty()) s += "。"
        return s.ifBlank { source }
    }

    /**
     * Whether [ja] is a recognition that mixed scripts — an English stretch written into kana ("My en is ready
     * is哔哔リ"). Translating it yields an invention, so a caller may show the original instead.
     */
    fun suspectSource(ja: String): Boolean {
        val kana = KANA.find(ja) != null
        val words = LATIN_WORD.findAll(ja).count()
        // a whole English clause written into a Japanese transcription: no translator can recover the sentence,
        // while a lone "Yeahや" is an ordinary mixed utterance and stays
        return kana && words >= 3
    }

    /**
     * Whether [ja] can serve as 【前文】 for the next sentence. A garbled recognition is not context but poison —
     * the translator follows what it reads there — and a two-character fragment carries nothing to follow.
     */
    fun usefulContext(ja: String): Boolean =
        ja.count { it.isLetterOrDigit() } >= 4 && !suspectSource(ja)

    /** A leftover English filler is dropped; a capitalised word (a name the translation kept) stays. */
    private fun dropFillers(s: String): String = LATIN_WORD.replace(s) { m ->
        val w = m.value
        if (w.first().isLowerCase() && w.lowercase().replace("'", "") in FILLERS) "" else w
    }

    private fun convert(s: String): String = buildString(s.length) {
        for (c in s) append(SIMPLIFIED[c] ?: c)
    }
}
