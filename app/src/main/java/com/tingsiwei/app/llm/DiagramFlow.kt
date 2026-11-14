package com.tingsiwei.app.llm

import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.db.AppDatabase
import com.tingsiwei.app.diagram.DiagramParser
import kotlinx.coroutines.CancellationException

/**
 * 图示化流程：已有的「思路」→ 一次 LLM 调用 → 分小节 + 每节可选解释图 → 存 notes.diagramJson。
 *
 * 独立于生成主流程（generate/revise）：失败只返回 false，绝不影响导图与思路；
 * 开关关闭或图示失败时，notes.diagramJson 保持 null，思路页回退整段显示。
 * 调用纪律：写库只覆盖 diagramJson 一列，不动 updatedAt（不把首页排序挤动，同 setCategory）。
 */
object DiagramFlow {

    /** 图示 JSON 含分节正文 + 5 类图结构，比纯文本长；预算给足但远小于整篇生成的 16k */
    private const val DiagramOutTokens = 4096

    suspend fun generate(
        noteId: Long,
        db: AppDatabase,
        settings: SettingsRepository,
        onStage: suspend (String) -> Unit = {},
    ): Boolean {
        val note = db.noteDao().byId(noteId) ?: return false
        val thinking = note.thinking
        if (thinking.isNullOrBlank()) return false
        val cfg = settings.current()
        if (!cfg.diagramsEnabled) return false

        onStage("正在拆分小节并配图…")
        val client = LlmClient(settings)
        val system = Prompts.diagramSystem()
        val user = Prompts.diagramUser(thinking)
        val doc = ask(client, cfg, system, user) ?: run {
            // 格式不对：纠正一次再问；仍不行就放弃（图示失败是软失败）
            onStage("图示格式不对，重试一次…")
            ask(client, cfg, system, user + Prompts.diagramReminder())
        } ?: return false

        db.noteDao().update(note.copy(diagramJson = DiagramParser.encode(doc)))
        return true
    }

    /** 问一次并解析；输出被截断时把带回来的半截内容交给 [DiagramParser.parse] 抢救 */
    private suspend fun ask(
        client: LlmClient,
        cfg: com.tingsiwei.app.data.AppSettings,
        system: String,
        user: String,
    ): com.tingsiwei.app.diagram.DiagramDoc? {
        val raw = try {
            client.chat(
                cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user,
                DiagramOutTokens, degradeRule = Prompts.diagramTrimRule(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmError.Truncated) {
            e.partialContent
        } catch (_: Exception) {
            return null
        }
        return DiagramParser.parse(raw)
    }
}
