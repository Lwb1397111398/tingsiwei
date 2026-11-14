package com.tingsiwei.app

import com.tingsiwei.app.transcribe.Timeline
import com.tingsiwei.app.transcribe.TimelineLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 思路 → 录音定位：术语命中、置信兜底、文档频率过滤、同分取最早、思路切段。 */
class TimelineLocatorTest {

    /** 造一条句级时间轴：文本按 10ms 一句排开，下标 i 的起点 = i * 10 */
    private fun segs(vararg texts: String): List<Timeline.Segment> =
        texts.mapIndexed { i, t -> Timeline.Segment(startMs = i * 10L, endMs = i * 10L + 9, text = t) }

    // ---------- locate ----------

    @Test
    fun `术语原样保留时定位到首次讲到的位置`() {
        val segments = segs(
            "同学们好，今天继续讲民法基本原则。",
            "上节课我们讲了平等原则和自愿原则。",
            "接下来这一章内容比较重要，大家注意听。",
            "民事活动应当遵循绿色原则，这是民法典第九条的明确要求。",
            "绿色原则强调节约资源、保护生态环境。",
            "比如垃圾分类、节能减排都属于这个范畴。",
            "好，下面我们讲下一个原则。",
            "公平原则要求民事主体合理确定各方的权利和义务。",
            "考试的时候这两个原则经常放在一起出题。",
            "最后再强调一遍，绿色原则和公平原则都是常考点。",
        )
        val query = "绿色原则：民事活动应当节约资源、保护生态环境，是民法典的基本原则之一。"
        assertEquals(3, TimelineLocator.locate(segments, query))
    }

    @Test
    fun `知识点讲了两遍时定位到与思路表述最吻合的那遍`() {
        // 老师先简短提、后展开呼应：思路的措辞（「要求不法侵害正在进行」）与第二遍更吻合，
        // argmax 应落第二遍，且回溯不能穿过擦边 1 分的过渡句（句 5 是「构成要件」话题）
        val segments = segs(
            "开场先说一句，今天讲正当防卫。",
            "第一部分先简单提一下正当防卫的概念。",
            "中间插一段犯罪构成的内容，起因条件、时间条件分别展开讲。",
            "把时间条件说清楚：不法侵害正在进行才成立。",
            "再看紧迫性要件的具体情形，这里要结合案例理解。",
            "现在回到正当防卫，展开讲它的五个构成要件。",
            "起因条件是存在现实的不法侵害，这是第一点。",
            "时间条件同样要求不法侵害正在进行，跟前面呼应。",
            "最后总结全篇，重点复习正当防卫。",
        )
        val query = "正当防卫的时间条件：要求不法侵害正在进行，这是防卫成立的关键要件。"
        assertEquals(7, TimelineLocator.locate(segments, query))
    }

    @Test
    fun `思路完全改写且无术语重叠时返回null`() {
        val segments = segs(
            "今天讲刑法总论的犯罪构成理论。",
            "主体要件方面，自然人要达到刑事责任年龄。",
            "主观要件分故意和过失两类。",
            "客体是国家、社会或个人的合法权益。",
            "客观方面包括行为、结果以及因果关系。",
        )
        val query = "本章梳理了责任承担的核心脉络与制度价值取向。"
        assertNull(TimelineLocator.locate(segments, query))
    }

    @Test
    fun `查询太短没有分辨力时返回null`() {
        val segments = segs("讲绿色原则。", "再说一遍绿色原则。", "绿色原则很重要。")
        // 「绿色原则」只有 3 个 gram，低于 MIN_KEYS
        assertNull(TimelineLocator.locate(segments, "绿色原则"))
    }

    @Test
    fun `高频套话gram均匀分布时不劫持术语定位`() {
        // 「这个问题的话」在 6 句里说了 3 遍（每句 5 个 gram），术语只在第 2 句命中 3 个
        // —— argmax 必须落术语，不能被句均分更高的套话句带走
        val segments = segs(
            "今天开始讲新的章节。",
            "这个问题的话大家要注意听。",
            "下面说绿色原则的含义。",
            "这个问题的话考试经常出。",
            "最后讲节约资源的做法。",
            "这个问题的话就先到这里。",
        )
        val query = "绿色原则要求民事活动节约资源保护生态环境，体现了可持续发展的价值取向，这是本章的考试重点。"
        assertEquals(2, TimelineLocator.locate(segments, query))
    }

    @Test
    fun `置信不足时返回null不乱跳`() {
        val segments = segs(
            "讲一下原则性问题。", // query 的 gram 最多擦边命中 1 个
            "天气不错，开始上课。",
            "上次作业整体完成得不错。",
            "今天讲一个新的章节。",
            "下课之前布置一下作业。",
        )
        val query = "绿色原则的规范基础与制度意义研究。"
        // 单句最高命中 1 < MIN_SCORE(3)
        assertNull(TimelineLocator.locate(segments, query))
    }

    @Test
    fun `空时间轴与空查询返回null`() {
        assertNull(TimelineLocator.locate(emptyList(), "绿色原则的相关论述。"))
        assertNull(TimelineLocator.locate(segs("正常的一句话内容。"), ""))
    }

    // ---------- splitForDisplay ----------

    @Test
    fun `按行切段并丢弃空行`() {
        val text = "第一点：绿色原则。\n\n第二点：公平原则。\n"
        assertEquals(listOf("第一点：绿色原则。", "第二点：公平原则。"), TimelineLocator.splitForDisplay(text))
    }

    @Test
    fun `超长段按句再切且贪心并回上限`() {
        val a = "甲".repeat(60) + "。" // 61 字符
        val b = "乙".repeat(60) + "。"
        val c = "丙".repeat(60) + "。"
        val out = TimelineLocator.splitForDisplay("$a$b$c") // 183 字符单行 → 必须细切
        assertTrue(out.all { it.length <= 160 })
        assertEquals("$a$b$c", out.joinToString(""))
        // a+b=122 ≤160 同段；b+c=122 超 160 → c 单独成段
        assertEquals(listOf(a + b, c), out)
    }

    @Test
    fun `不超上限的行原样保留不重切`() {
        val line = "短的一行。"
        assertEquals(listOf(line), TimelineLocator.splitForDisplay(line))
    }
}
