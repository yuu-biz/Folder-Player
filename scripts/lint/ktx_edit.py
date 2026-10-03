#!/usr/bin/env python3
"""Rewrites `X.edit().a(..).b(..).apply()` into `X.edit { a(..); b(..) }` (androidx.core KTX) in the given files."""
import re
import sys


def split_top(chain):
    parts, depth, cur, in_str = [], 0, "", None
    i = 0
    while i < len(chain):
        c = chain[i]
        if in_str:
            cur += c
            if c == "\\":
                cur += chain[i + 1]; i += 2; continue
            if c == in_str:
                in_str = None
        elif c in "\"'":
            in_str = c; cur += c
        elif c in "([{":
            depth += 1; cur += c
        elif c in ")]}":
            depth -= 1; cur += c
        elif c == "." and depth == 0:
            if cur:
                parts.append(cur)
            cur = ""
        else:
            cur += c
        i += 1
    if cur:
        parts.append(cur)
    return parts


def find_close(s, i):
    depth, in_str = 0, None
    while i < len(s):
        c = s[i]
        if in_str:
            if c == "\\":
                i += 2; continue
            if c == in_str:
                in_str = None
        elif c == '"':
            in_str = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def rewrite(src):
    out, pos, changed = [], 0, 0
    for m in re.finditer(r"\.edit\(\)", src):
        if m.start() < pos:
            continue
        j = m.end()
        chain_start = j
        # walk a chain of .name(args) until .apply() / .commit()
        k = j
        calls = []
        ok = False
        while True:
            mm = re.match(r"\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\(", src[k:])
            if not mm:
                break
            name = mm.group(1)
            open_idx = k + mm.end() - 1
            close_idx = find_close(src, open_idx)
            if close_idx < 0:
                break
            if name in ("apply", "commit") and src[open_idx + 1:close_idx].strip() == "":
                ok = bool(calls)
                end = close_idx + 1
                break
            calls.append(src[k + mm.start(1) - 0 if False else k:close_idx + 1].strip().lstrip(".").strip())
            k = close_idx + 1
        if not ok:
            continue
        out.append(src[pos:m.start()])
        out.append(".edit { " + "; ".join(calls) + " }")
        pos = end
        changed += 1
    out.append(src[pos:])
    return "".join(out), changed


for path in sys.argv[1:]:
    s = open(path, encoding="utf-8").read()
    n, c = rewrite(s)
    if c:
        if "import androidx.core.content.edit" not in n:
            n = re.sub(r"(\npackage [^\n]+\n)", r"\1\nimport androidx.core.content.edit\n", n, count=1)
        open(path, "w", encoding="utf-8", newline="\n").write(n)
    print(f"{path}: {c}")
