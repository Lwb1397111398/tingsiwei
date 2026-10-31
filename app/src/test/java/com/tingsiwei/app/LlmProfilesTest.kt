package com.tingsiwei.app

import com.tingsiwei.app.data.LlmProfile
import com.tingsiwei.app.data.decodeLlmProfiles
import com.tingsiwei.app.data.encodeLlmProfiles
import com.tingsiwei.app.data.removeLlmProfile
import com.tingsiwei.app.data.upsertLlmProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/** LLM 配置方案的 JSON 编解码与增删改；全是纯函数，JVM 直接跑 */
class LlmProfilesTest {

    private val plain = LlmProfile("DeepSeek", "https://api.deepseek.com/v1", "sk-abc123", "deepseek-chat")

    @Test
    fun `round trip keeps all fields`() {
        val list = listOf(
            plain,
            LlmProfile("中转站", "https://api.csid.cc/v1", "sk-\"引号\"反斜杠\\换行\n制表\t", "模型名🚀"),
            LlmProfile("控制符", "u", "k", "m" + 0x01.toChar() + "尾"),
        )
        assertEquals(list, decodeLlmProfiles(encodeLlmProfiles(list)))
    }

    @Test
    fun `empty list encodes to empty array and decodes back`() {
        assertEquals("[]", encodeLlmProfiles(emptyList()))
        assertEquals(emptyList<LlmProfile>(), decodeLlmProfiles("[]"))
        assertEquals(emptyList<LlmProfile>(), decodeLlmProfiles(""))
        assertEquals(emptyList<LlmProfile>(), decodeLlmProfiles("   "))
    }

    @Test
    fun `decode tolerates whitespace between tokens`() {
        val pretty = """
            [
              { "name" : "A" , "url" : "u" , "key" : "k" , "model" : "m" }
            ]
        """.trimIndent()
        assertEquals(listOf(LlmProfile("A", "u", "k", "m")), decodeLlmProfiles(pretty))
    }

    @Test
    fun `decode ignores unknown fields`() {
        val json = """[{"name":"A","extra":"x","url":"u","key":"k","model":"m"}]"""
        assertEquals(listOf(LlmProfile("A", "u", "k", "m")), decodeLlmProfiles(json))
    }

    @Test
    fun `decode returns empty on malformed input instead of throwing`() {
        // 半截、垃圾、值类型不对、尾部有垃圾、顶层数组都没有——一律空列表，不抛异常打断设置页
        val bad = listOf(
            "[",
            "[{",
            "not json at all",
            """[{"name":123,"url":"u","key":"k","model":"m"}]""",
            """[{"name":"A","url":"u","key":"k","model":"m"}] trailing""",
            """{"name":"A"}""",
        )
        bad.forEach { assertEquals("损坏输入应按空列表处理：$it", emptyList<LlmProfile>(), decodeLlmProfiles(it)) }
    }

    @Test
    fun `decode skips nameless entries`() {
        // 缺 name 的项解析不报错，但没有名字无法点选，跳过
        val json = encodeLlmProfiles(listOf(LlmProfile("", "u", "k", "m"), plain))
        assertEquals(listOf(plain), decodeLlmProfiles(json))
    }

    @Test
    fun `upsert appends new and replaces same name in place`() {
        val a = LlmProfile("A", "u1", "k1", "m1")
        val b = LlmProfile("B", "u2", "k2", "m2")
        assertEquals(listOf(a, b), upsertLlmProfile(listOf(a), b))
        val b2 = LlmProfile("B", "u9", "k9", "m9")
        assertEquals(listOf(a, b2), upsertLlmProfile(listOf(a, b), b2))
        // 不改入参
        val original = listOf(a, b)
        upsertLlmProfile(original, b2)
        assertEquals(listOf(a, b), original)
    }

    @Test
    fun `remove deletes by name and keeps others`() {
        val a = LlmProfile("A", "u1", "k1", "m1")
        val b = LlmProfile("B", "u2", "k2", "m2")
        assertEquals(listOf(b), removeLlmProfile(listOf(a, b), "A"))
        assertEquals(listOf(a, b), removeLlmProfile(listOf(a, b), "不存在"))
        assertEquals(emptyList<LlmProfile>(), removeLlmProfile(listOf(a), "A"))
    }
}
