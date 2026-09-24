import os
import re
import sys

def unpack_code(content: str, base_dir: str = "."):
    # 匹配 Markdown 中的代码块，捕获首行文件路径及代码内容
    pattern = re.compile(
        r"```(?:java|JAVA)?\s*\n//\s*File:\s*([^\r\n]+)\r?\n(.*?)```",
        re.DOTALL
    )

    matches = pattern.findall(content)
    if not matches:
        print("未检测到符合格式的代码块！格式示例：\n```\n// File: ./path/to/File.java\n...\n```")
        return

    for file_path_str, code in matches:
        file_path_str = file_path_str.strip()
        # 清理路径前导修饰
        if file_path_str.startswith("./"):
            file_path_str = file_path_str[2:]
        elif file_path_str.startswith(".\\"):
            file_path_str = file_path_str[2:]

        full_path = os.path.normpath(os.path.join(base_dir, file_path_str))
        parent_dir = os.path.dirname(full_path)

        # 确保目标目录存在
        if parent_dir and not os.path.exists(parent_dir):
            os.makedirs(parent_dir, exist_ok=True)

        # 写入并覆盖文件（处理非标准空白符）
        cleaned_code = code.replace("\u00a0", " ").strip() + "\n"
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(cleaned_code)

        print(f"已写入: {full_path}")

if __name__ == "__main__":
    # 支持从文件读取或标准输入传入
    if len(sys.argv) > 1:
        with open(sys.argv[1], "r", encoding="utf-8") as f:
            text = f.read()
    else:
        text = sys.stdin.read()

    unpack_code(text)