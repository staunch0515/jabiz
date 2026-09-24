#!/bin/zsh
OUT_FILE="project_code.md"

echo "# Project Source Code Export\n" > "$OUT_FILE"

find . -type f -name "*.java" ! -path "*/target/*" ! -path "*/build/*" ! -path "*/.*" | while IFS= read -r file; do
  printf '## File: `%s`\n\n```java\n' "$file" >> "$OUT_FILE"
  cat "$file" >> "$OUT_FILE"
  printf '\n```\n\n' >> "$OUT_FILE"
done
