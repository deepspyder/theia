package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.ExportNaming
import com.spektralab.android.core.model.ExportSize
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File

/** Uses actual accessibility clicks/text entry, including cancellation and validation. */
internal class ExportOptionsUiAudit(private val test: Instrumentation, private val output: File) {
    private fun root(): AccessibilityNodeInfo? { test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }
    private fun await(label: String, ready: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!ready()) { check(SystemClock.elapsedRealtime() - start < 45000) { "R02 UI timeout: $label" }; Thread.sleep(80) }
    }
    private fun scroll(node: AccessibilityNodeInfo?, action: Int): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) if (scroll(node.getChild(i), action)) return true
        return false
    }
    private fun reveal(label: String) {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) repeat(25) {
            if (find(label) != null) return
            scroll(root(), direction); test.waitForIdleSync(); Thread.sleep(70)
        }
        error("R02 UI missing $label")
    }
    private fun click(label: String) {
        reveal(label)
        fun action(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                var parent: AccessibilityNodeInfo? = node
                while (parent != null) { if (parent.isClickable) return parent; parent = parent.parent }
            }
            for (i in 0 until node.childCount) action(node.getChild(i))?.let { return it }
            return null
        }
        val node = checkNotNull(action(root())) { "Not clickable: $label" }
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled: $label" }
        test.waitForIdleSync(); Thread.sleep(250)
    }
    private fun editable(node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
        return null
    }
    private fun enter(value: String) {
        check(checkNotNull(editable()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        test.waitForIdleSync(); Thread.sleep(180)
    }
    private fun enabled(label: String): Boolean {
        var node = find(label)
        check(node != null) { "Missing enabled state: $label" }
        while (node != null) {
            if (!node.isEnabled) return false
            node = node.parent
        }
        return true
    }
    private fun capture(name: String) {
        test.waitForIdleSync(); Thread.sleep(200)
        checkNotNull(test.uiAutomation.takeScreenshot()).also { bitmap ->
            File(output, "r02-ui-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    fun run(): String {
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await("engine") { vm.engine != null && !vm.busy }
        val originalRecipes = vm.recipes
        val originalSize = vm.exportCustomSize
        val originalNaming = vm.exportNaming
        val name = "R02界面测试${System.nanoTime()}"
        return try {
            click("更多操作"); click("测试图")
            await("photo") { vm.hasPhoto && !vm.busy }
            click("导出")
            click("分辨率"); click("自定义尺寸")
            enter("0")
            capture("invalid-size")
            check(!enabled("应用")) { "Invalid size can be applied" }
            click("短边"); enter("317"); click("应用")
            check(vm.exportCustomSize == ExportSize(ExportSize.Kind.SHORT_EDGE, 317.0))
            click("分辨率"); click("自定义尺寸"); enter("641"); click("取消")
            check(vm.exportCustomSize?.value == 317.0) { "Cancelling changed the size" }
            capture("size")
            click("高级设置"); click("文件命名"); click("自定义组合")
            click("胶片"); click("日期"); click("将日期前移")
            check(vm.exportNaming.tokens == listOf(ExportNaming.Token.ORIGINAL, ExportNaming.Token.DATE, ExportNaming.Token.FILM))
            click("分隔符"); click("短横线")
            check(vm.exportNaming.separator == "-")
            reveal("文件命名"); capture("naming")
            val quickLabel = "高清预览 (3400px) · 3400 px 检查"
            reveal(quickLabel)
            repeat(4) { scroll(root(), AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); test.waitForIdleSync() }
            val quickBounds = Rect().also { checkNotNull(find(quickLabel)).getBoundsInScreen(it) }
            var button = checkNotNull(find("导出当前照片"))
            while (!button.isClickable) button = button.parent ?: error("Missing export action")
            val buttonBounds = Rect().also(button::getBoundsInScreen)
            check(quickBounds.bottom <= buttonBounds.top) { "Quick actions overlap pinned export: $quickBounds / $buttonBounds" }
            capture("quick-actions")
            click("保存配方"); enter(name); click("保存")
            await("saved recipe") { !vm.recipesBusy && vm.recipes.any { it.name == name } }
            check(vm.recipeError == null)
            click("管理配方"); capture("manager")
            click("重命名 $name"); enter(name + "改名"); click("重命名")
            await("renamed recipe") { !vm.recipesBusy && vm.recipes.any { it.name == name + "改名" } }
            click("删除配方 ${name}改名"); capture("delete-confirm")
            click("取消")
            check(vm.recipes.any { it.name == name + "改名" })
            click("删除配方 ${name}改名"); click("删除配方")
            await("deleted recipe") { !vm.recipesBusy && vm.recipes.none { it.name.startsWith(name) } }
            click("完成")
            check(find("导出当前照片") != null)
            "Custom-size invalid/apply/cancel, token add/reorder/separator, recipe save/rename/delete/cancel, scrolling and pinned export action passed through real UI"
        } finally {
            // Remove only the test's own saved recipe, preserving pre-existing app data.
            val remaining = vm.recipes.filter { it.name.startsWith(name) }
            for (recipe in remaining) {
                test.runOnMainSync { vm.deleteRecipe(recipe) {} }
                await("fixture cleanup") { !vm.recipesBusy }
            }
            check(vm.recipes == originalRecipes) { "UI test changed unrelated recipes" }
            test.runOnMainSync { vm.exportCustomSize = originalSize; vm.exportNaming = originalNaming; activity.finish() }
        }
    }
}
