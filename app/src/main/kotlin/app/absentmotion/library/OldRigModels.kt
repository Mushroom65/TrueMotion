package app.absentmotion.library

import app.absentmotion.model.Scene
import app.absentmotion.platform.Context
import java.io.File

/** Keep models saved in 0.15 accessible in My resources after removing the duplicate shelf. */
internal fun migrateOldRigModels(context: Context, projects: ProjectStore) {
    migrateOldRigModels(File(context.filesDir, "rig-models"), projects) { context.contentResolver.openInputStream(app.absentmotion.platform.Uri.parse(it)) }
}

internal fun migrateOldRigModels(old: File, projects: ProjectStore, open: (String) -> java.io.InputStream?) {
    if (!old.isDirectory) return
    val templates = TemplateStore(old, open, requireLocalMedia = true)
    for (model in templates.list()) {
        val marker = File(old, "${model.id}/migrated-to-assets")
        if (marker.exists()) continue
        runCatching {
            val id = projects.createElement(Scene(model.scene.node.with("type", "element")))
            try {
                projects.save(id, templates.localize(model.scene, projects.mediaDir(id)).let { Scene(it.node.with("type", "element")) })
                marker.writeText(id)
            } catch (error: Exception) { projects.delete(id); throw error }
        }
    }
}
