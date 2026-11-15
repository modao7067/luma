package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.agent.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BlankSessionDraft(
    var key: String = UUID.randomUUID().toString(), var cli: String = "Codex", var cwd: String = "/workspace",
    var text: String = "", val images: MutableList<StoredImage> = mutableListOf(),
    val skills: MutableList<DraftSkill> = mutableListOf(),
)
internal data class SessionNavigation(
    var initialized: Boolean = false, var selectedId: String? = null,
    val blank: BlankSessionDraft = BlankSessionDraft(),
)
internal fun decodeNavigation(root: JSONObject?): SessionNavigation {
    if (root == null) return SessionNavigation()
    val blank = root.getJSONObject("blank")
    val cli = blank.getString("cli"); require(cli in CliCatalog.ids) { tr("空白会话 CLI 无效，原索引已保留", "Invalid blank session CLI; original index preserved") }
    return SessionNavigation(true, root.stringOrNull("selectedId"), BlankSessionDraft(
        blank.getString("key"), cli, blank.getString("cwd"), blank.optString("text"),
        blank.optJSONArray("images")?.objects()?.map { StoredImage(it.getString("id"), it.getString("uri"), it.getString("path"), it.getString("name")) }?.toMutableList() ?: mutableListOf(),
        blank.optJSONArray("skills")?.objects()?.map { DraftSkill(it.getString("id"), it.getString("name"), it.getString("path")) }?.toMutableList() ?: mutableListOf()))
}
internal fun encodeNavigation(nav: SessionNavigation): JSONObject = obj("selectedId" to nav.selectedId,
    "blank" to obj("key" to nav.blank.key, "cli" to nav.blank.cli, "cwd" to nav.blank.cwd, "text" to nav.blank.text,
        "images" to JSONArray(nav.blank.images.map { obj("id" to it.id, "uri" to it.uri, "path" to it.path, "name" to it.name) }),
        "skills" to JSONArray(nav.blank.skills.map { obj("id" to it.id, "name" to it.name, "path" to it.path) })))
internal fun restoredSelection(nav: SessionNavigation, records: Collection<SessionRecord>, legacy: String?, environment: String): String? {
    val current = records.filter { it.environment == environment }
    return if (nav.initialized) nav.selectedId?.takeIf { id -> current.any { it.id == id } }
        else legacy?.takeIf { id -> current.any { it.id == id } } ?: current.lastOrNull()?.id
}
