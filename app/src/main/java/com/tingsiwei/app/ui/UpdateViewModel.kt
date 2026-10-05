package com.tingsiwei.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingsiwei.app.App
import com.tingsiwei.app.BuildConfig
import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.update.ReleaseInfo
import com.tingsiwei.app.data.update.UpdateCheckResult
import com.tingsiwei.app.data.update.UpdateChecker
import com.tingsiwei.app.data.update.UpdateException
import com.tingsiwei.app.data.update.UpdateInstaller
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用自更新：设置页「应用更新」区的状态机。
 * 检查（手动/启动静默）→ 弹窗 → 下载（进度）→ 唤起系统安装器。
 * 与 SettingsViewModel 分开：AppNav 顶层持有同一实例，更新弹窗在任何页面都能弹。
 */
class UpdateViewModel : ViewModel() {

    private val repo: SettingsRepository = App.get().settings
    private val context = App.get()

    val currentVersion: String = BuildConfig.VERSION_NAME

    var autoCheck by mutableStateOf(true)
        private set
    var hasToken by mutableStateOf(false)
        private set
    var tokenInput by mutableStateOf("")
    var checking by mutableStateOf(false)
        private set
    /** 设置页状态行；statusError = true 时红色显示 */
    var status by mutableStateOf<String?>(null)
        private set
    var statusError by mutableStateOf(false)
        private set
    /** 非空 = 弹出更新弹窗 */
    var available by mutableStateOf<ReleaseInfo?>(null)
        private set
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0)
        private set
    /** 已下载完整的安装包（弹窗据此把「立即更新」换成「安装更新」） */
    var downloadedApk by mutableStateOf<File?>(null)
        private set
    var downloadError by mutableStateOf<String?>(null)
        private set
    /** 缺「安装未知应用」权限：已打开系统设置页，回来后点「安装更新」重试 */
    var needsInstallPermission by mutableStateOf(false)
        private set
    var guideOpen by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val s = repo.current()
            autoCheck = s.updateAutoCheck
            hasToken = s.hasGithubToken
        }
    }

    /** App 启动静默检查：开了开关且距上次检查超 24 小时才联网；出错不打扰 */
    fun autoCheckIfNeeded() {
        viewModelScope.launch {
            try {
                val s = repo.current()
                if (!s.updateAutoCheck) return@launch
                if (System.currentTimeMillis() - repo.updateLastCheckedMs() < AUTO_CHECK_INTERVAL_MS) return@launch
                check(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 启动静默检查失败不影响使用，保持安静
            }
        }
    }

    fun checkNow() {
        viewModelScope.launch {
            try {
                check(silent = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("检查更新失败：${e.message ?: "未知错误"}", error = true)
            }
        }
    }

    private suspend fun check(silent: Boolean) {
        if (checking || downloading) return
        checking = true
        status = null
        downloadError = null
        try {
            val s = repo.current()
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.fetchLatest(
                    apiBase = s.updateApiBase.ifBlank { UpdateChecker.DEFAULT_API_BASE },
                    repo = UpdateChecker.REPO,
                    token = repo.githubTokenOrNull(),
                    currentVersionCode = BuildConfig.VERSION_CODE,
                )
            }
            repo.markUpdateChecked()
            when (result) {
                is UpdateCheckResult.UpToDate ->
                    if (!silent) setStatus("已是最新版本（v${result.remoteVersionName}）")
                is UpdateCheckResult.Available -> {
                    available = result.release
                    if (!silent) setStatus("发现新版本 v${result.release.versionName}")
                }
            }
        } catch (e: UpdateException) {
            if (!silent) setStatus(e.message ?: "检查更新失败", error = true)
        } finally {
            checking = false
        }
    }

    fun saveToken() {
        if (tokenInput.isBlank()) return
        viewModelScope.launch {
            try {
                repo.saveGithubToken(tokenInput)
                hasToken = true
                tokenInput = ""
                setStatus("令牌已保存，可以点「检查更新」试试")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("令牌保存失败：${e.message ?: "未知错误"}", error = true)
            }
        }
    }

    fun clearToken() {
        viewModelScope.launch {
            try {
                repo.clearGithubToken()
                hasToken = false
                setStatus("已清除 GitHub 令牌")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("清除令牌失败：${e.message ?: "未知错误"}", error = true)
            }
        }
    }

    /** 自动检查开关：即点即存 */
    fun changeAutoCheck(enabled: Boolean) {
        autoCheck = enabled
        viewModelScope.launch {
            try {
                repo.setUpdateAutoCheck(enabled)
            } catch (e: Exception) {
                autoCheck = !enabled
                setStatus("保存开关失败：${e.message ?: "未知错误"}", error = true)
            }
        }
    }

    /** 下载完成后自动唤起安装器；缺权限先跳系统开关页 */
    fun startDownload() {
        val release = available ?: return
        if (downloading) return
        viewModelScope.launch {
            downloading = true
            downloadError = null
            progress = 0
            needsInstallPermission = false
            try {
                val token = repo.githubTokenOrNull()
                val dest = File(context.cacheDir, "updates/update-${release.versionCode}.apk")
                withContext(Dispatchers.IO) {
                    UpdateChecker.downloadApk(release.apkUrl, token, dest, release.apkSize) { pct ->
                        progress = pct
                    }
                }
                downloadedApk = dest
                tryInstall()
            } catch (e: UpdateException) {
                downloadError = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadError = "下载失败：${e.message ?: "未知错误"}"
            } finally {
                downloading = false
            }
        }
    }

    /**
     * 安装已下载的 APK。缺「安装未知应用」权限时返回 false 并跳系统开关页；
     * 用户打开后回到 App 再点一次「安装更新」即可。
     */
    fun tryInstall() {
        val file = downloadedApk ?: return
        if (!UpdateInstaller.canInstall(context)) {
            needsInstallPermission = true
            UpdateInstaller.openInstallPermissionSettings(context)
            return
        }
        needsInstallPermission = false
        UpdateInstaller.install(context, file)
        // 安装器接管前台，收起弹窗；下载文件保留，装失败回来还能直接重装
        available = null
    }

    fun dismissUpdate() {
        available = null
        needsInstallPermission = false
        downloadError = null
    }

    fun openGuide() {
        guideOpen = true
    }

    fun closeGuide() {
        guideOpen = false
    }

    private fun setStatus(text: String, error: Boolean = false) {
        status = text
        statusError = error
    }

    companion object {
        /** 自动检查节流：24 小时一次 */
        const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
