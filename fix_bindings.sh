#!/bin/bash
# Fix Glitch
sed -i '' 's/\([^.]\)previewContainer\./\1binding.editorPreview.previewContainer./g' app/src/main/java/com/example/videoeditorapp/GlitchTemplateEditorActivity.kt
sed -i '' 's/\([^.]\)playerViewPreview\./\1binding.editorPreview.playerView./g' app/src/main/java/com/example/videoeditorapp/GlitchTemplateEditorActivity.kt

# Fix Image
sed -i '' 's/\([^.]\)previewContainer\./\1binding.editorPreview.previewContainer./g' app/src/main/java/com/example/videoeditorapp/ImageTemplateEditorActivity.kt

# Fix Music
sed -i '' 's/\([^.]\)previewContainer\./\1binding.editorPreview.previewContainer./g' app/src/main/java/com/example/videoeditorapp/MusicTemplateEditorActivity.kt

# Fix Text
sed -i '' 's/\([^.]\)previewContainer\./\1binding.editorPreview.previewContainer./g' app/src/main/java/com/example/videoeditorapp/TextTemplateEditorActivity.kt

# Fix News
sed -i '' 's/\([^.]\)previewContainer\./\1binding.editorPreview.previewContainer./g' app/src/main/java/com/example/videoeditorapp/NewsTemplateEditorActivity.kt

echo "All files fixed!"
