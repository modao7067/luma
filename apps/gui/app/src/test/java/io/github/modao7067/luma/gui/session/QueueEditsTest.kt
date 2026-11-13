package io.github.modao7067.luma.gui.session
import io.github.modao7067.luma.gui.agent.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class QueueEditsTest {
    @Test fun editedTextReparsesMentionsAndPreservesImagesAndOtherNativeInputs() {
        val original = CodexProjection.inputs("old @/old", listOf("/image.png"), "/work").put(obj("type" to "mention", "name" to "attached", "path" to "/attached")).put(obj("type" to "skill", "name" to "test", "path" to "/skill"))
        val edited = QueueEdits.replaceText(original, "new @/new", "/work").objects()
        assertEquals("new @/new", edited.first().getString("text"))
        assertEquals(listOf("/new", "/attached"), edited.filter { it.optString("type") == "mention" }.map { it.getString("path") })
        assertEquals("/image.png", edited.first { it.optString("type") == "localImage" }.getString("path"))
        assertTrue(edited.any { it.optString("type") == "skill" })
        assertEquals("old @/old", original.getJSONObject(0).getString("text"))
    }
    @Test fun emptyTextKeepsNativeImage() {
        assertEquals(1, QueueEdits.replaceText(CodexProjection.inputs("a", listOf("/image"), "/"), "", "/").length())
        assertEquals(0, QueueEdits.replaceText(JSONArray(), "", "/").length())
    }
}
