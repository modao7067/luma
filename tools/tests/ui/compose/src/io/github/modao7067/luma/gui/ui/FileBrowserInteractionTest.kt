package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FileBrowserInteractionTest {
    @get:Rule val rule=createComposeRule()
    private val entries=listOf(
        FileEntry("src","/workspace/app/src",true),
        FileEntry(".git","/workspace/app/.git",true),
        FileEntry("Main.kt","/workspace/app/Main.kt",false,size=2048),
        FileEntry("README.md","/workspace/app/README.md",false,size=10),
    )

    @Test fun browseModeOpensFilesAndBreadcrumbsJumpToAncestors() {
        val browsed=mutableListOf<String>(); val previewed=mutableListOf<String>()
        val actions=object:CockpitActions by previewActions {
            override fun browseFiles(path:String) {browsed+=path}
            override fun previewFile(path:String) {previewed+=path}
        }
        rule.setContent {MaterialTheme {FileBrowserScreen(FileBrowser("/workspace/app",entries),actions)}}
        rule.onNodeWithText("文件").assertIsDisplayed()
        rule.onNodeWithText(".git").assertDoesNotExist()
        rule.onNodeWithText("Main.kt").performClick()
        rule.onNodeWithText("src").performClick()
        rule.onNodeWithText("workspace").performClick()
        rule.runOnIdle {
            assertEquals(listOf("/workspace/app/Main.kt"),previewed)
            assertEquals(listOf("/workspace/app/src","/workspace"),browsed)
        }
    }

    @Test fun pickModeSelectsSeveralItemsIncludingFoldersAndReferencesThemTogether() {
        val referenced=mutableListOf<List<String>>(); val previewed=mutableListOf<String>()
        val actions=object:CockpitActions by previewActions {
            override fun referenceFiles(paths:List<String>) {referenced+=paths}
            override fun previewFile(path:String) {previewed+=path}
        }
        rule.setContent {MaterialTheme {FileBrowserScreen(FileBrowser("/workspace/app",entries,picking=true),actions)}}
        rule.onNodeWithText("引用文件").assertIsDisplayed()
        rule.onNodeWithTag("file-browser-reference").assertIsNotEnabled()
        rule.onNodeWithText("Main.kt").performClick()
        rule.onNodeWithText("README.md").performClick()
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.onNodeWithText("引用 3 项").performClick()
        rule.runOnIdle {
            assertTrue(previewed.isEmpty())
            assertEquals(1,referenced.size)
            assertEquals(setOf("/workspace/app/Main.kt","/workspace/app/README.md","/workspace/app/src"),referenced.single().toSet())
        }
    }

    @Test fun longPressInBrowseModeStartsSelection() {
        val referenced=mutableListOf<List<String>>()
        val actions=object:CockpitActions by previewActions {override fun referenceFiles(paths:List<String>) {referenced+=paths}}
        rule.setContent {MaterialTheme {FileBrowserScreen(FileBrowser("/workspace/app",entries),actions)}}
        rule.onNodeWithText("Main.kt").performTouchInput {longClick()}
        rule.onNodeWithText("引用 1 项").performClick()
        rule.runOnIdle {assertEquals(listOf(listOf("/workspace/app/Main.kt")),referenced)}
    }
}
