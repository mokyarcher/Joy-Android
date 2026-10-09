package com.joy.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.joy.android.data.AppMode
import com.joy.android.data.JoySettingsStore
import com.joy.android.data.SessionStore
import com.joy.android.overlay.OverlayController
import java.util.concurrent.atomic.AtomicBoolean

object JoyRoutes {
    const val HOME = "home"
    const val REPLY = "reply"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val MODEL_SETTINGS = "settings/model"
    const val IMAGE_SETTINGS = "settings/image"
    const val PERSONA_SETTINGS = "settings/persona"
    const val GROUP_BOT_SETTINGS = "settings/group_bot"
    const val OVERLAY_SETTINGS = "settings/overlay"
}

@Composable
fun JoyApp() {
    val context = LocalContext.current.applicationContext
    val store = remember { JoySettingsStore(context) }
    val sessionStore = remember { SessionStore(context) }
    val viewModel: ReplyViewModel = viewModel(
        factory = ReplyViewModel.factory(store, sessionStore),
    )
    val nav = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val autoStartOnce = remember { AtomicBoolean(false) }

    LaunchedEffect(settings.autoStartOverlay) {
        if (!settings.autoStartOverlay) return@LaunchedEffect
        if (!autoStartOnce.compareAndSet(false, true)) return@LaunchedEffect
        if (OverlayController.canDrawOverlays(context) && !OverlayController.isRunning) {
            OverlayController.start(context)
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppBackground()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = nav, startDestination = JoyRoutes.HOME) {
        composable(JoyRoutes.HOME) {
            when (settings.appMode) {
                AppMode.ASSISTANT -> ReplyScreen(
                    viewModel = viewModel,
                    onOpenSettings = { nav.navigate(JoyRoutes.SETTINGS) },
                    onOpenSessions = { nav.navigate(JoyRoutes.SESSIONS) },
                )
                AppMode.GROUP_BOT -> GroupBotHomeScreen(
                    viewModel = viewModel,
                    onOpenSettings = { nav.navigate(JoyRoutes.SETTINGS) },
                    onOpenBotSettings = { nav.navigate(JoyRoutes.GROUP_BOT_SETTINGS) },
                )
            }
        }
        // 兼容旧深链
        composable(JoyRoutes.REPLY) {
            ReplyScreen(
                viewModel = viewModel,
                onOpenSettings = { nav.navigate(JoyRoutes.SETTINGS) },
                onOpenSessions = { nav.navigate(JoyRoutes.SESSIONS) },
            )
        }
        composable(JoyRoutes.SESSIONS) {
            SessionsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
            )
        }
        composable(JoyRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
                onOpenSessions = { nav.navigate(JoyRoutes.SESSIONS) },
                onOpenOverlaySettings = { nav.navigate(JoyRoutes.OVERLAY_SETTINGS) },
                onOpenModelSettings = { nav.navigate(JoyRoutes.MODEL_SETTINGS) },
                onOpenImageSettings = { nav.navigate(JoyRoutes.IMAGE_SETTINGS) },
                onOpenPersonaSettings = { nav.navigate(JoyRoutes.PERSONA_SETTINGS) },
                onOpenGroupBotSettings = { nav.navigate(JoyRoutes.GROUP_BOT_SETTINGS) },
            )
        }
        composable(JoyRoutes.OVERLAY_SETTINGS) {
            OverlaySettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
            )
        }
        composable(JoyRoutes.MODEL_SETTINGS) {
            ModelSettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
            )
        }
        composable(JoyRoutes.IMAGE_SETTINGS) {
            ImageSettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
            )
        }
        composable(JoyRoutes.PERSONA_SETTINGS) {
            PersonaSettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
            )
        }
        composable(JoyRoutes.GROUP_BOT_SETTINGS) {
            GroupBotSettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
