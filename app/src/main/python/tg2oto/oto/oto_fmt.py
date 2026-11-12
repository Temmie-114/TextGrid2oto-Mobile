"""oto.ini 五字段位序风格开关。

上游生成的字段序列是 `offset, preutterance, -right, consonant, cross`：

- 第 3 位（UTAU 里是 overlap）写的是右边界长度的**相反数**，恒为负；
- 第 5 位（UTAU 里是 cutoff）写的是 cross，是一个很小的正数。

OpenUtau 能接受这种写法（它对负 overlap 有专门语义），所以作为默认值保留。
但原版 UTAU 严格按 `offset, preutterance, overlap, consonant, cutoff` 解析，
负 overlap + 小正 cutoff 会让音源表现异常（例如整段被裁掉或口型错位）。

因此提供两种风格：

- ``ou``（默认）：原样输出，保持既有结果不变。
- ``utau``：把第 3 位与第 5 位互换，得到
  ``offset, preutterance, overlap(=cross，小正数), consonant, cutoff(=-right，负值)``，
  即 UTAU 文档里的标准写法和符号约定。
"""

STYLE = "ou"


def set_style(name):
    """由 runner 在每次任务开始时设置。未知值一律回落到 ou。"""
    global STYLE
    STYLE = "utau" if str(name).strip().lower() in ("utau", "classic", "utau标准") else "ou"


def convert_line(line):
    """按当前风格重排一行 oto。非标准 6 字段行原样返回。"""
    if STYLE != "utau" or not isinstance(line, str) or "=" not in line:
        return line
    head, rest = line.split("=", 1)
    trailing_nl = "\n" if rest.endswith("\n") else ""
    parts = rest.rstrip("\n").split(",")
    if len(parts) != 6:
        return line
    alias, left, fixed, right_neg, cons, cross = parts
    return f"{head}={alias},{left},{fixed},{cross},{cons},{right_neg}{trailing_nl}"


def convert_lines(lines):
    return [convert_line(x) for x in lines]
