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


def fill_initial_aliases(oto, log=None):
    """给「有裸条目、但没有 - 音节」的音节补一条句首条目。

    UTAU / OpenUtau 的句首音符只能匹配「- x」。而 oto 生成时「- x」只来自
    每条连单录音的第一个音节——从没当过首音节的音节（ei、ai、an、en、ou 等）
    就整批没有句首条目，句首一用就缺音。这里按裸条目复制一条，参数完全一致：
    两者本来就是同一段音头，只是别名不同。
    """
    have = {o[1][2:] for o in oto if o[1].startswith("- ")}
    out = []
    added = 0
    for o in oto:
        out.append(o)
        alias = o[1]
        # 「_韵母」是延音条目，不是音节本身，不能当成音节去补「- 音节」，
        # 否则会写出「- _i」「- _a」这种没有任何引擎认识的别名。
        if alias.startswith("- ") or alias.startswith("_") or " " in alias:
            continue
        if alias in have:
            continue
        out.append([o[0], "- " + alias] + list(o[2:]))
        have.add(alias)
        added += 1
    if added and log:
        log(f"[oto] 补齐句首条目 +{added} 条「- 音节」")
    return out
