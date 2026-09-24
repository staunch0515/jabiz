#### 方式 A：外部工具快捷触发（External Tools）

可以将上述命令直接做成 IDEA 的右键菜单：

1. 打开 `Settings` (快捷键 `Cmd + ,`) -> **Tools** -> **External Tools**。
2. 点击 `+` 新增：
   - **Name**: `Export to Single File`
   - **Program**: `/bin/zsh`
   - **Arguments**: -c "find . -name '*.java' ! -path '*/target/*' ! -path '*/build/*' -exec printf '\n\n// File: {}\n\n' \; -exec cat {} + > $ProjectFileDir$/project_code.txt"
   - **Working directory**: $FileDir$
3. 点击 **OK** 保存。之后在项目根目录右键 -> **External Tools** -> **Export to Single File** 即可一键生成。