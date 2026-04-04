#!/bin/bash
# Script to replace all hardcoded white text colors with theme-aware colors

cd /Users/manpreet/Desktop/AndroidStudioProjects/VideoEditorApp/app/src/main/res/layout

# Replace in all layout files
for file in *.xml; do
    if [ -f "$file" ]; then
        sed -i '' 's/android:textColor="@android:color\/white"/android:textColor="?android:textColorPrimary"/g' "$file"
    fi
done

echo "Text color replacement complete!"
