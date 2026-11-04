package app.absentmotion.library

import app.absentmotion.platform.Context
import java.io.File

/** Uses the same atomic storage and media localization as reusable templates. */
fun rigModelStore(context: Context): TemplateStore = TemplateStore(File(context.filesDir, "rig-models"), { uri -> context.contentResolver.openInputStream(app.absentmotion.platform.Uri.parse(uri)) }, requireLocalMedia = true)
