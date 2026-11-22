package io.github.modao7067.luma.gui.session

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import io.github.modao7067.luma.gui.GuiProduct
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

internal class SessionManagementBinding(context: Context, server: (String) -> SessionManagementServer) {
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    val transport = server(token)
    init {
        val root = GuiProduct.agentRelative()
        val directory = File(context.filesDir, "runtime/$root/sessions").apply { mkdirs(); Os.chmod(absolutePath, 448) }
        val binding = AtomicFile(File(directory, "binding.json"))
        val output = binding.startWrite()
        try {
            output.write(JSONObject().put("host", "127.0.0.1").put("port", transport.port).put("token", token).toString().toByteArray())
            binding.finishWrite(output); Os.chmod(binding.baseFile.absolutePath, 384)
        } catch (e: Exception) { binding.failWrite(output); transport.close(); throw e }
        for (name in listOf("luma-sessions", "SKILL.md")) {
            val target = File(context.filesDir, if (name == "SKILL.md") "runtime/$root/skills/luma-sessions/SKILL.md" else "runtime/bin/luma-sessions")
            val parent = target.parentFile!!
            parent.mkdirs()
            val text = context.assets.open("sessions/$name").bufferedReader().use { it.readText() }
                .replace("/opt/luma/agent", GuiProduct.runtimePath(context.filesDir, root))
            val mode = if (name == "SKILL.md") 384 else 448
            val temp = File(parent, ".${target.name}.${java.util.UUID.randomUUID()}.tmp")
            try {
                temp.writeText(text)
                Os.chmod(temp.absolutePath, mode)
                if (!temp.renameTo(target)) throw java.io.IOException("Failed to install $name")
            } catch (e: Exception) {
                temp.delete()
                transport.close()
                throw e
            }
        }
    }
}
