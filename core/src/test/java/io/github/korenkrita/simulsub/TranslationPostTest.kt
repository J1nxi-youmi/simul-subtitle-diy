package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The last clean-up before a translation reaches the screen (see [TranslationPost]). */
class TranslationPostTest {
    @Test
    fun anEmptyAnswerShowsTheOriginalInsteadOfABlankLine() {
        // qwen3.8-max answered "" for two of the sixteen replayed segments: a blank caption is worse than the source
        assertEquals("あ、長半語ど超半語。", TranslationPost.tidy("", "あ、長半語ど超半語。"))
        assertEquals("까광 입니다。", TranslationPost.tidy("   ", "까광 입니다。"))
    }

    @Test
    fun aLeftoverEnglishFillerIsDroppedButANameIsKept() {
        // logged: "我不聊英语了，但我觉得 maybe 我还能行。"
        assertEquals(
            "不聊英语了，但我觉得我还能行。",
            TranslationPost.tidy("不聊英语了，但我觉得 maybe 我还能行。", "The no more English. I I think maybe I can."),
        )
        // a capitalised word stays: it is the only trace of a name in the answer
        assertEquals("Doinb那一个，不过……", TranslationPost.tidy("Doinb那一个，不过……", "Takeick that one, but."))
        // pasted English text that is the answer itself is never emptied
        assertEquals("OK, 我知道了。", TranslationPost.tidy("OK, 我知道了。", "OK, 我知道了。"))
    }

    @Test
    fun strayKanaInAChineseAnswerIsNoiseAndGoes() {
        assertEquals("我准备好了。", TranslationPost.tidy("我准备好の了。", "My en is ready."))
    }

    @Test
    fun traditionalCharactersBecomeSimplified() {
        // logged from a Korean segment, which was the only traditional answer in 101 captions
        assertEquals("中文也没学过，对吧。", TranslationPost.tidy("中文也沒學過，對吧。", "중국 어도, 안 한 는데요."))
        assertEquals("这个时间，谢谢大家。", TranslationPost.tidy("這個時間，謝謝大家。", "この時間、ありがとう。"))
    }

    @Test
    fun separatorsLeftByARemovalAreTidiedAndTheFullStopSurvives() {
        assertEquals("我觉得我还能行。", TranslationPost.tidy("我觉得，maybe 我还能行。", "I think maybe I can."))
        assertEquals("好", TranslationPost.tidy("，好，", "はい"))
    }

    @Test
    fun anAnswerThatIsOnlyNoiseFallsBackToTheOriginal() {
        assertEquals("そればな。", TranslationPost.tidy("，", "そればな。"))
        assertEquals("で。", TranslationPost.tidy("maybe", "で。"))
    }

    @Test
    fun aCleanAnswerIsLeftUntouched() {
        val zh = "谢谢大家来看小高，我会继续努力的，希望大家多多支持～"
        assertEquals(zh, TranslationPost.tidy(zh, "谢谢大家来看小高。"))
        assertEquals("我是大阪出生的日本人哦。", TranslationPost.tidy("我是大阪出生的日本人哦。", "と大阪生まれの日本人でございます。"))
    }

    @Test
    fun aRecognitionThatMixedScriptsIsFlagged() {
        // logged: an English stretch transcribed into kana, translated into an invention ("我来开麦了")
        assertTrue(TranslationPost.suspectSource("My en is ready is哔哔リ."))
        assertTrue(TranslationPost.suspectSource("Sorry sorryり sorryりmy my English isベベ butト."))
        // plain Japanese, plain English and plain Chinese are all fine
        assertFalse(TranslationPost.suspectSource("と大阪生まれの日本人でございます。"))
        assertFalse(TranslationPost.suspectSource("I learned English from six years old."))
        assertFalse(TranslationPost.suspectSource("然后我的小学和初中，我是九年义务教育的留学生。"))
        assertFalse(TranslationPost.suspectSource("Yeahや."))
    }
}
