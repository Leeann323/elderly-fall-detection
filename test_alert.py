# -*- coding: utf-8 -*-
"""给手机 App 发一条测试告警，验证「电脑 → ntfy → 手机」整条链路。

用法：
    python test_alert.py                       # 读环境变量 NTFY_TOPIC
    python test_alert.py ltc-fall-6gnhxpna     # 直接指定 topic

成功后手机应在 2~15 秒内弹出「跌倒（冲击型） · 客厅」的红色告警。
"""
import json
import os
import sys

import requests

from datetime import datetime

SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh")
topic = sys.argv[1].strip() if len(sys.argv) > 1 else os.environ.get("NTFY_TOPIC", "").strip()

if not topic:
    raise SystemExit("请提供 topic：python test_alert.py <topic>   （或先 set NTFY_TOPIC=...）")

payload = {
    "kind": "A",
    "kind_name": "跌倒（冲击型）",
    "area": "客厅",
    "eid": "TEST-%s" % datetime.now().strftime("%Y%m%d-%H%M%S"),
    "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    "hold": 8.0,
    "score": 0.99,
}

url = "%s/%s" % (SERVER.rstrip("/"), topic)

try:
    resp = requests.post(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"User-Agent": "FallAlert-PC/1.0",
                 "Content-Type": "application/json; charset=utf-8"},
        timeout=8,
    )
except Exception as exc:
    raise SystemExit("[fail] 发送失败：%s\n       检查电脑能不能访问 %s" % (exc, SERVER))

print("[http] %s" % resp.status_code)
if resp.status_code >= 300:
    raise SystemExit("[fail] 中转服务拒绝：%s" % resp.text[:200])

print("[ok] 已发往 %s" % url)
print("[payload] " + json.dumps(payload))     # ASCII 转义输出，避免 GBK 控制台报错
print("")
print("如果手机没反应，依次检查：")
print("  1) App 里的 Topic 是否与上面完全一致（区分大小写）")
print("  2) 是否已点过「启动监听」（状态栏应有常驻通知）")
print("  3) 通知权限是否已授予、告警通道是否被静音")
print("  4) 手机是否把 App 的后台活动限制了（设置→电池→不限制）")
