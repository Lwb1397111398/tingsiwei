package com.tingsiwei.app

import com.tingsiwei.app.transcribe.ModelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TTS 下载清单的守门测试：路径不能重复、关键文件必须在、字节数必须为正 */
class ModelManagerTtsTest {

    @Test
    fun `TTS 清单完整且互不重复`() {
        val paths = ModelManager.TTS_FILES.map { it.relPath }
        assertEquals(paths.size, paths.toSet().size)
        assertTrue("缺少 int8 模型", paths.contains("model.int8.onnx"))
        assertTrue("缺少音色库 voices.bin", paths.contains("voices.bin"))
        assertTrue("缺少中文词典 lexicon-zh.txt", paths.contains("lexicon-zh.txt"))
        assertTrue("缺少 tokens.txt", paths.contains("tokens.txt"))
        assertTrue("缺少 jieba 词典目录", paths.any { it.startsWith("dict/") })
        assertTrue("缺少 espeak 注音数据", paths.any { it.startsWith("espeak-ng-data/") })
        assertTrue(
            "缺少朗读规则 fst",
            paths.containsAll(listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst")),
        )
        assertTrue(ModelManager.TTS_FILES.all { it.bytes > 0 })
        assertEquals(ModelManager.TTS_FILES.sumOf { it.bytes }, ModelManager.TTS_TOTAL_BYTES)
    }
}
