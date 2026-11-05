# -*- coding: utf-8 -*-
"""把手机 App 里的 Topic 原样写进 ntfy_topic.txt，杜绝手打出错。

用法：
    python set_topic.py                    # 从 Windows 剪贴板读（先在电脑上 Ctrl+V）
    python set_topic.py ltc-fall-6gnhxpna  # 直接指定
    python set_topic.py --test             # 读剪贴板 + 写完立刻发一条测试告警

推荐流程（跨设备不手打）：
    手机 App 点「复制」→ 微信发给"文件传输助手" → 电脑上复制那段文字
    → python set_topic.py --test
    只要手机 topic 填得对，运行完手机应该立刻响。
"""
import json
import os
import re
import subprocess
import sys
from datetime import datetime

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
TOPIC_FILE = os.path.join(HERE, "ntfy_topic.txt")
SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh").rstrip("/")


def from_clipboard():
    try:
        out = subprocess.check_output(
            ["powershell", "-NoProfile", "-Command", "Get-Clipboard -Raw"],
            text=True, encoding="utf-8", errors="replace", timeout=15)
        return out
    except Exception:
        return ""


def normalize(raw):
    if not raw:
        return ""
    first = raw.strip().splitlines()[0] if raw.strip() else ""
    s = first.strip()
    for prefix in ("https://ntfy.sh/", "http://ntfy.sh/",
                   "https://" + SERVER.replace("https://", "").replace("http://", "") + "/"):
        if s.startswith(prefix):
            s = s[len(prefix):]
    s = s.strip("\"'` \t　")
    return s


def is_valid(topic):
    return bool(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", topic or ""))


def main():
    args = [a for a in sys.argv[1:]]
    do_test = "--test" in args
    args = [a for a in args if a != "--test"]

    if args:
        raw = args[0]
        src = "命令行参数"
    else:
        raw = from_clipboard()
        src = "剪贴板"

    topic = normalize(raw)
    if not topic:
        print("[fail] 没拿到 Topic（来源：%s）。" % src)
        print("       先在电脑上复制手机 App 里那串 Topic，再运行本脚本；")
        print("       或者：python set_topic.py ltc-fall-xxxx")
        return 1
    if not is_valid(topic):
        print("[fail] Topic 不合法：%r" % topic)
        print("       ntfy 只允许字母、数字、- 和 _，长度 1~64。请检查是否混进了空格或中文。")
        return 1

    with open(TOPIC_FILE, "w", encoding="utf-8") as fh:
        fh.write("# 手机端 App「跌倒告警」里填的 Topic（由 set_topic.py 写入）\n")
        fh.write("# 本文件已被 .gitignore 排除\n")
        fh.write(topic + "\n")

    print("[ok] 来源：%s" % src)
    print("[ok] 已写入 %s" % TOPIC_FILE)
    print("[ok] Topic = %s" % topic)
    print("[ok] 推送地址 = %s/%s" % (SERVER, topic))
    print("")
    print("    手机 App 顶部必须也是这个字符串：%s" % topic)

    if do_test:
        payload = {
            "kind": "A",
            "kind_name": "配对测试",
            "area": "客厅",
            "eid": "PAIR-%s" % datetime.now().strftime("%H%M%S"),
            "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "hold": 8.0,
            "score": 0.99,
        }
        try:
            r = requests.post("%s/%s" % (SERVER, topic),
                              data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                              headers={"User-Agent": "FallAlert-PC/1.0",
                                       "Content-Type": "application/json; charset=utf-8"},
                              timeout=8)
            if r.status_code < 300:
                print("[ok] 已向该 Topic 发出一条测试告警 -> 手机应在 2~15 秒内弹通知")
            else:
                print("[warn] 发送被拒：%s %s" % (r.status_code, r.text[:150]))
                return 1
        except Exception as exc:
            print("[warn] 发送失败：%s" % exc)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
