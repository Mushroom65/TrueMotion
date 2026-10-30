package app.absentmotion.editor

fun EditorState.openMotionPath() {
    if (selected == null || selected?.kind == "audio") return
    playing = false
    endGesture()
    motionPathOpen = true
    framePhotoOpen = false
    trueStudioOpen = false
    addOpen = false
    easingOpen = false
    panel = EditorPanel.MoveTransform
}
