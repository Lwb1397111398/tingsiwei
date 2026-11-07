package com.tingsiwei.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tingsiwei.app.ui.theme.Motion

object Routes {
    const val HOME = "home"

    /** 录音/文字输入带可选分类参数（首页筛选继承），null 时用默认值 -1 表示未分类 */
    const val RECORD = "record?categoryId={categoryId}"
    const val TEXT_INPUT = "textinput?categoryId={categoryId}"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{noteId}"
    const val CATEGORY_MAP = "categorymap/{categoryId}"
    const val CATEGORY_MANAGE = "categorymanage"

    fun detail(noteId: Long) = "detail/$noteId"
    fun record(categoryId: Long?) = "record?categoryId=${categoryId ?: -1L}"
    fun textInput(categoryId: Long?) = "textinput?categoryId=${categoryId ?: -1L}"
    fun categoryMap(categoryId: Long) = "categorymap/$categoryId"
}

/**
 * 页面切换过渡：统一的「抽入抽回」——子页面从下方 1/8 屏滑入 + 渐显，被盖住的首页
 * 轻微缩小（层级下沉感）；返回时反向。时长走 Motion.Emphasized，快而不糊。
 * 首页自身（pop 回来时）用 scaleIn 从 0.98 回弹，与退出时的 scaleOut 接续成完整运动。
 */
@Composable
fun AppNav() {
    val nav = rememberNavController()
    // 应用自更新：顶层持有同一个实例——更新弹窗在任何页面都能弹，启动时静默检查一次
    val updateVm: UpdateViewModel = viewModel()
    LaunchedEffect(Unit) { updateVm.autoCheckIfNeeded() }
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = {
            slideInVertically(Motion.emphasized()) { it / 8 } + fadeIn(tween(Motion.Standard))
        },
        exitTransition = {
            scaleOut(Motion.emphasized(), targetScale = 0.98f) + fadeOut(tween(Motion.Standard))
        },
        popEnterTransition = {
            scaleIn(Motion.emphasized(), initialScale = 0.98f) + fadeIn(tween(Motion.Standard))
        },
        popExitTransition = {
            slideOutVertically(Motion.emphasized()) { it / 8 } + fadeOut(tween(Motion.Standard))
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenNote = { nav.navigate(Routes.detail(it)) },
                onOpenRecord = { cid -> nav.navigate(Routes.record(cid)) },
                onOpenTextInput = { cid -> nav.navigate(Routes.textInput(cid)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenCategoryMap = { nav.navigate(Routes.categoryMap(it)) },
                onOpenCategoryManage = { nav.navigate(Routes.CATEGORY_MANAGE) },
            )
        }
        composable(
            Routes.RECORD,
            arguments = listOf(navArgument("categoryId") {
                type = NavType.LongType
                defaultValue = -1L
            }),
        ) { entry ->
            RecordScreen(
                initialCategoryId = entry.arguments?.getLong("categoryId")?.takeIf { it > 0 },
                onSaved = { id ->
                    nav.navigate(Routes.detail(id)) { popUpTo(Routes.HOME) }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.TEXT_INPUT,
            arguments = listOf(navArgument("categoryId") {
                type = NavType.LongType
                defaultValue = -1L
            }),
        ) { entry ->
            TextInputScreen(
                initialCategoryId = entry.arguments?.getLong("categoryId")?.takeIf { it > 0 },
                onSaved = { id ->
                    nav.navigate(Routes.detail(id)) { popUpTo(Routes.HOME) }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DETAIL) { entry ->
            val noteId = entry.arguments?.getString("noteId")?.toLongOrNull() ?: 0L
            DetailScreen(noteId = noteId, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.CATEGORY_MAP,
            arguments = listOf(navArgument("categoryId") { type = NavType.LongType }),
        ) { entry ->
            CategoryMapScreen(
                categoryId = entry.arguments?.getLong("categoryId") ?: 0L,
                onOpenNote = { nav.navigate(Routes.detail(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CATEGORY_MANAGE) {
            CategoryManageScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, updateVm = updateVm)
        }
    }
    UpdateDialog(vm = updateVm)
}
