package com.dshpet.android.util

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 为"非 Activity 上下文"里创建的 ComposeView（悬浮窗菜单/气泡）提供
 * LifecycleOwner / SavedStateRegistryOwner —— 否则 Compose 组合时会抛
 * "ViewTreeLifecycleOwner not found" 崩溃。
 *
 * 注意：WindowRecomposer 在 ComposeView.onAttachedToWindow 时就会沿 ViewTree
 * 查找 owners，发生在组合之前 —— 仅在 setContent 里 CompositionLocalProvider
 * 提供是来不及的。必须在 addView 之前调用 [attachComposeHost] 设置
 * ViewTreeLifecycleOwner / ViewTreeSavedStateRegistryOwner。
 *
 * ⚠️ 必须是 class 而不是 object：
 * [SavedStateRegistry.consumeRestoredStateForKey] 在同一个 registry 实例上
 * 同一个 key 只能消费一次（Compose 组合时用它读取 saveable 状态）。
 * 若这里做成单例，悬浮窗菜单 / 气泡 / 快捷聊天等多个 ComposeView 复用同一个
 * SavedStateRegistry，第二个组合再消费同一个 key 就会抛
 * IllegalStateException: You can consumeRestoredStateForKey only once per instance。
 * 因此每个宿主 View 必须拥有独立的 owner 实例（见 [attachComposeHost]）。
 */
internal class ComposeHostOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    init {
        // SavedStateRegistry 的官方时序（ComponentActivity 同款）：
        // 1) performAttach 必须在 Lifecycle==INITIALIZED 时调用（内部校验）
        // 2) performRestore(null) 结束恢复阶段 —— 此后 consumeRestoredStateForKey
        //    才可用（Compose 组合必需）
        // 3) 然后才把 Lifecycle 推到 RESUMED
        controller.performAttach()
        controller.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
}

/**
 * 悬浮窗 ComposeView addView 前必须调用：把宿主 owners 挂到 ViewTree。
 *
 * 每次调用都会新建一个 [ComposeHostOwner] 实例 —— 保证每个 ComposeView
 * （以及每次重新 setContent）都拥有独立的 SavedStateRegistry，
 * 避免多个宿主复用同一 registry 时触发
 * "You can consumeRestoredStateForKey only once per instance"。
 */
fun View.attachComposeHost() {
    val owner = ComposeHostOwner()
    setViewTreeLifecycleOwner(owner)
    setViewTreeSavedStateRegistryOwner(owner)
}

/**
 * 为组合提供宿主 owners。
 *
 * 这里必须提供与 [attachComposeHost] 挂到 ViewTree 上的**同一个**实例：
 * Compose 内部会用 ViewTree 上的 owner 去 consumeRestoredStateForKey，
 * 若 CompositionLocal 指向另一个实例，两边会各用各的 registry，
 * 状态保存/恢复行为就会错乱。
 */
@Composable
fun ProvideComposeHost(content: @Composable () -> Unit) {
    val view = LocalView.current
    val owner = remember(view) {
        // LocalView 是 AndroidComposeView，向上回溯能拿到 ComposeView 上设置的 owner。
        // 只有当 ViewTree 上的 lifecycle / savedState 两个 owner 确实是同一个
        // ComposeHostOwner（attachComposeHost 的不变量）时才复用，否则自建一个自洽的。
        val treeLifecycle = view.findViewTreeLifecycleOwner()
        val treeSavedState = view.findViewTreeSavedStateRegistryOwner()
        if (treeLifecycle is ComposeHostOwner && treeSavedState === treeLifecycle) {
            treeLifecycle
        } else {
            ComposeHostOwner()
        }
    }
    CompositionLocalProvider(
        LocalLifecycleOwner provides owner,
        LocalSavedStateRegistryOwner provides owner,
        content = content,
    )
}
