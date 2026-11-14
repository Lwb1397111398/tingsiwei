package com.tingsiwei.app.llm

import com.tingsiwei.app.data.AppSettings
import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.db.AppDatabase
import com.tingsiwei.app.diagram.DiagramParser
import com.tingsiwei.app.diagram.DiagramSection
import com.tingsiwei.app.diagram.DetailDoc
import com.tingsiwei.app.diagram.DetailParser
import kotlinx.coroutines.CancellationException

/**
 * 详细讲解流程：录音转写原文 + 现有小节清单 → 一次 LLM 调用 → 每节详解（可追加补充小节）
 * → 存 notes.detailJson。界面用「点卡片展开详解」消费它，听书/复制用的文字版由
 * composeFullText 本地拼出，同源同文。
 *
 * 独立于生成主流程（generate/revise）：失败只返回 false，绝不影响导图与思路；
 * 开关关闭或失败时 notes.detailJson 保持 null，卡片无展开内容、听书回退思路原文。
 * 调用纪律：写库只覆盖 detailJson 一列，不动 updatedAt（同 DiagramFlow/setCategory）。
 */
object DetailFlow {

    /**
     * 详解比图示长得多（每节 200~600 字 × 最多 11 节）；预算给足，
     * 真被截断时 chatWithDegrade 自动抬预算带体量规则重问，parser 还能抢救半截。
     */
    private const val DetailOutTokens = 8192

    /**
     * @param allowTruncate 自动接力（转写完成→图示→详解）传 false：原文装不下窗口时直接跳过——
     *   截断的原文会让录音后半段整段漏讲，宁可不出详解也不能悄悄出一份缺角的；
     *   手动按钮（思路页「生成详细讲解」）传 true：用户明确点了按钮，Tokens.fit 截断硬来，
     *   覆盖能覆盖的部分也好过什么都不出。
     */
    suspend fun generate(
        noteId: Long,
        db: AppDatabase,
        settings: SettingsRepository,
        allowTruncate: Boolean = false,
        onStage: suspend (String) -> Unit = {},
    ): Boolean {
        val note = db.noteDao().byId(noteId) ?: return false
        val content = note.content
        if (content.isNullOrBlank()) return false
        val cfg = settings.current()
        if (!cfg.detailsEnabled) return false
        // 详解以图示的小节为骨架：小节都没有，无从讲起（图示是前置步骤，失败时本步自然跳过）
        val sections = DiagramParser.decode(note.diagramJson)?.sections.orEmpty()
        if (sections.isEmpty()) return false

        val system = Prompts.detailSystem(cfg.allowExpand)
        val sectionsText = buildSectionsText(sections)
        val window = LlmPolicy.windowFor(cfg.llmContextWindow, cfg.llmConservative)
        if (!allowTruncate &&
            Tokens.estimate(content) + Tokens.estimate(system) + Tokens.estimate(sectionsText) + DetailOutTokens > window
        ) {
            return false
        }

        onStage("正在对照原文写详细讲解…")
        val client = LlmClient(settings)
        val user = Prompts.detailUser(content, note.title, sectionsText)
        val doc = ask(client, cfg, system, user) ?: run {
            // 格式不对：纠正一次再问；仍不行就放弃（详解失败是软失败）
            onStage("讲解格式不对，重试一次…")
            ask(client, cfg, system, user + Prompts.detailReminder())
        } ?: return false

        // 标题对齐：对得上现有小节的排回正文，对不上的标「补充」；全对不上 = 模型重写了标题，当失败处理
        val marked = DetailParser.markExtras(doc, sections.map { it.title }) ?: return false
        db.noteDao().update(note.copy(detailJson = DetailParser.encode(marked)))
        return true
    }

    /** 〔N〕标题 + 小节正文，供模型逐节对照（正文保留——它定义了「原有内容」的边界） */
    private fun buildSectionsText(sections: List<DiagramSection>): String = buildString {
        sections.forEachIndexed { i, s ->
            appendLine("〔${i + 1}〕${s.title.trim()}")
            if (s.text.isNotBlank()) appendLine(s.text.trim())
        }
    }.trim()

    /** 问一次并解析；输出被截断时把带回来的半截内容交给 [DetailParser.parse] 抢救 */
    private suspend fun ask(
        client: LlmClient,
        cfg: AppSettings,
        system: String,
        user: String,
    ): DetailDoc? {
        val raw = try {
            client.chat(
                cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user,
                DetailOutTokens, degradeRule = Prompts.detailTrimRule(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmError.Truncated) {
            e.partialContent
        } catch (_: Exception) {
            return null
        }
        return DetailParser.parse(raw)
    }
}
