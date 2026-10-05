package com.tingsiwei.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val HOME = "home"
    const val RECORD = "record"
    const val TEXT_INPUT = "textinput"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{noteId}"
    fun detail(noteId: Long) = "detail/$noteId"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    // 应用自更新：顶层持有同一个实例——更新弹窗在任何页面都能弹，启动时静默检查一次
    val updateVm: UpdateViewModel = viewModel()
    LaunchedEffect(Unit) { updateVm.autoCheckIfNeeded() }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenNote = { nav.navigate(Routes.detail(it)) },
                onOpenRecord = { nav.navigate(Routes.RECORD) },
                onOpenTextInput = { nav.navigate(Routes.TEXT_INPUT) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.RECORD) {
            RecordScreen(
                onSaved = { id ->
                    nav.navigate(Routes.detail(id)) { popUpTo(Routes.HOME) }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.TEXT_INPUT) {
            TextInputScreen(
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
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, updateVm = updateVm)
        }
    }
    UpdateDialog(vm = updateVm)
}
