# -*- coding: utf-8 -*-
"""oto 条目去重。

连单录音（一个 wav 里连着录多个音节，VCV / CVVC 的录音方式就是如此）在生成
oto 时，同一音节会在一句里反复出现，各家生成器都是逐音节写条目，于是同一个
wav 内会出现多行同名别名。UTAU / OpenUtau 加载时只采用其中一行，其余行携带
的参数永远读不到——留着只是让「本该有多少条」看起来对不上。

这里按（音频文件名, 别名）去重，默认保留同文件内最先出现的一条。
"""

from __future__ import annotations


def dedup_oto(oto, keep="first", label=""):
    """按（音频文件, 别名）去重，返回去重后的条目列表。

    keep="first" 保留最先出现的一条；keep="last" 保留最后出现的一条
    （后者替换原位，因此条目顺序仍按首次出现的次序）。
    label 用于日志前缀，便于分辨是哪个文件触发的。
    """
    seen = {}
    out = []
    dropped = 0
    for line in oto:
        try:
            wav, rest = line.split("=", 1)
            alias = rest.split(",", 1)[0]
        except ValueError:
            out.append(line)
            continue
        key = (wav, alias)
        if key in seen:
            dropped += 1
            if keep == "last":
                out[seen[key]] = line
            continue
        seen[key] = len(out)
        out.append(line)
    if dropped:
        prefix = f"{label}: " if label else ""
        print(f"{prefix}去重：丢弃 {dropped} 条重复别名"
              f"（同文件内保留{'最后' if keep == 'last' else '最先'}出现的一条）")
    return out
