# -*- coding: utf-8 -*-
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Leeann323
# https://github.com/Leeann323/elderly-fall-detection
"""基于 AI 人体骨架姿态的居家老人无穿戴跌倒监测预警系统

Elderly Fall Detection & Alert System via Skeleton Pose Estimation

处理链路：单目摄像头 -> MediaPipe 33 点骨架 -> 尺度归一化几何特征
-> 三通道判决（A 冲击型 / B 缓慢滑落型 / C 坐位垂头型）-> 状态机确认
-> 骨架图落盘 + 钉钉/企业微信异步推送。

隐私：原始视频帧不落盘、不上云；告警图片只包含纯黑背景上的骨架连线。

运行：python elderly_fall_monitor.py   （按 q 或 Esc 退出）
依赖：opencv-python, mediapipe, numpy<2, requests
许可：Apache License 2.0（可商用，须署名并保留 NOTICE，见 NOTICE 文件）
"""

 # -*- coding: utf-8 -*-
import collections
import math
import os
import queue
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from statistics import median

import cv2
import mediapipe as mp
import numpy as np
import requests

# ============================== 配置 ==============================
VIDEO_SOURCE = 0                 # 0=本机摄像头；也可填 'rtsp://...' 或 'test.mp4'
AREA = "客厅"                    # 监测区域：客厅 / 卧室 / 卫生间 / 书房
DINGTALK_WEBHOOK = ""            # 钉钉或企业微信机器人地址，留空则只本地打印
ALERT_DIR = "alerts"             # 骨架图落盘目录（可在清理策略中定期删除）
PUBLIC_IMAGE_BASE = ""           # 若填 'http://<局域网IP>:8000/alerts'，告警中内嵌图片链接

# --- 姿态与判决阈值（均以 L_ref = 肩中—髋中 距离为单位） ---
HH_GROUND = 1.3                  # 贴地门限：头离地比低于此值才算倒地
SEATED_HH_MAX = 2.6              # 坐位 Hh 上界：再高视为站立，C 通道不成立
HIP_SEATED_MAX = 1.25            # 坐位髋高上界
SEAT_ANGLE = 55.0                # 头颈下垂角门限
STILL_LIMIT = 1.0                # C 通道触发用的微动上限（L/s）
STILL_HOLD = 1.2                 # C 通道保持阶段的微动上限（L/s）
MOTION_REF = 1.5                 # 评分中"静止"的参考速度（L/s）
SCORE_TH = 0.70                  # 姿态评分门限
IMPACT_DROP = 2.5                # A 通道：下坠速度门限（L/s）
IMPACT_OMEGA = 90.0              # A 通道：躯干角速度门限（deg/s）
IMPACT_WINDOW = 1.5              # A 通道：冲击事件的有效期（s）
CROSS_WINDOW = 2.0               # B 通道：跨越贴地线的有效期（s）
CONFIRM_SEC = 8.0                # 跌倒确认窗口（s）；客厅可改 15.0
SEAT_CONFIRM = 60.0              # 坐位晕厥确认窗口（s）
REVOKE_SEC = 3.0                 # 终末姿势失效多久算"已恢复"（s）
DEBOUNCE_FRAMES = 3              # 触发去抖帧数
ALERT_COOLDOWN = 60.0            # 同一事件重复告警抑制（s）
ALERT_TIMEOUT = 300.0            # 告警状态最长保持时间，超时强制复位（s）
LOST_RESET_SEC = 30.0            # 目标丢失超过该时长则复位状态机（s）
SQUAT_REMIND_SEC = 900.0         # 久蹲旁路提醒（s）
CALIB_FRAMES = 120               # 尺度基准标定帧数
CALIB_SEC = 15.0                 # 标定最长等待（s）
SMOOTH_N = 5                     # 关键点滑动平均窗口
MIN_VIS = 0.5                    # 关键点可见性门限
SEAT_AREAS = ("客厅", "卧室", "书房")   # 允许启用 C 通道的区域
ENABLE_C = AREA in SEAT_AREAS
FPS_FALLBACK = 25.0

mp_pose = mp.solutions.pose
POSE = mp_pose.Pose(static_image_mode=False, model_complexity=1,
                    smooth_landmarks=True,
                    min_detection_confidence=0.5, min_tracking_confidence=0.5)


# ============================== 特征 ==============================
class Pt:
    """轻量关键点，便于滑动平均后复用同一套几何计算。"""
    __slots__ = ("x", "y", "visibility")

    def __init__(self, x, y, visibility=1.0):
        self.x, self.y, self.visibility = x, y, visibility


@dataclass
class Frame:
    theta: float      # 躯干中轴倾角（deg，0=直立，90=水平）
    R: float          # 关键点外接框宽高比
    hh: float         # 头离地比 Hh
    hip_h: float      # 髋离地比
    knee: float       # 膝角（deg）
    neck: float       # 头颈下垂角（deg）
    still: float      # 微动速度（L/s）
    drop: float       # 头离地比下降速度（L/s）
    omega: float      # 躯干角速度（deg/s）
    grounded: bool    # 终末"贴地"是否成立
    seated: bool      # 是否处于坐位
    score: float      # 姿态评分


def _vis(p, i):
    return getattr(p[i], "visibility", 1.0)


def _angle(a, b, c):
    """∠abc，单位为度。"""
    v1 = (a[0] - b[0], a[1] - b[1])
    v2 = (c[0] - b[0], c[1] - b[1])
    n = math.hypot(*v1) * math.hypot(*v2)
    if n < 1e-6:
        return 180.0
    cos = (v1[0] * v2[0] + v1[1] * v2[1]) / n
    return math.degrees(math.acos(max(-1.0, min(1.0, cos))))


def clamp(x, lo=0.0, hi=1.0):
    return max(lo, min(hi, x))


def torso_px(lm, w, h):
    """肩中—髋中距离（像素），用作人体尺度基准。"""
    sx = (lm[11].x + lm[12].x) / 2 * w
    sy = (lm[11].y + lm[12].y) / 2 * h
    hx = (lm[23].x + lm[24].x) / 2 * w
    hy = (lm[23].y + lm[24].y) / 2 * h
    return math.hypot(hx - sx, hy - sy)


def posture_features(lm, w, h, l_ref, prev, dt, hh_prev, theta_prev):
    """33 点骨架 → 倾角 / 宽高比 / 头离地比 / 髋高 / 膝角 / 头颈角 / 微动 / 速度。"""
    def P(i):
        return (lm[i].x * w, lm[i].y * h)

    neck = ((P(11)[0] + P(12)[0]) / 2, (P(11)[1] + P(12)[1]) / 2)
    hip = ((P(23)[0] + P(24)[0]) / 2, (P(23)[1] + P(24)[1]) / 2)
    dx, dy = hip[0] - neck[0], hip[1] - neck[1]
    torso = math.hypot(dx, dy) + 1e-6
    theta = math.degrees(math.atan2(abs(dx), abs(dy) + 1e-6))

    xs = [p.x * w for p in lm]
    ys = [p.y * h for p in lm]
    R = (max(xs) - min(xs)) / max(max(ys) - min(ys), 1e-6)

    feet = max(ys[27:33])
    feet_vis = min(_vis(lm, i) for i in range(27, 33))
    nose = P(0)
    hh = (feet - nose[1]) / l_ref
    hip_h = (feet - hip[1]) / l_ref

    knees = [_angle(P(a), P(b), P(c)) for a, b, c in ((23, 25, 27), (24, 26, 28))
             if min(_vis(lm, a), _vis(lm, b), _vis(lm, c)) > MIN_VIS]
    knee = sum(knees) / len(knees) if knees else 180.0

    v1 = (nose[0] - neck[0], nose[1] - neck[1])
    v2 = (-dx, -dy)
    cos = (v1[0] * v2[0] + v1[1] * v2[1]) / (math.hypot(*v1) * torso)
    neck_deg = math.degrees(math.acos(max(-1.0, min(1.0, cos))))

    still = 0.0
    if prev is not None and dt > 0:
        disp = [math.hypot(p.x * w - q.x * w, p.y * h - q.y * h)
                for p, q in zip(lm, prev)]
        still = float(np.mean(disp)) / torso / dt

    drop = (hh_prev - hh) / dt if (hh_prev is not None and dt > 0) else 0.0
    omega = abs(theta - theta_prev) / dt if (theta_prev is not None and dt > 0) else 0.0

    grounded = hh < HH_GROUND
    if feet_vis <= MIN_VIS:        # 脚被家具遮挡时，改用"扁平 + 头低于髋 + 膝伸直"补充判据
        grounded = theta > 65 and nose[1] > hip[1] and knee > 140
    seated = (HH_GROUND <= hh < SEATED_HH_MAX and theta < 45
              and hip_h < HIP_SEATED_MAX and knee > 75)

    f1 = clamp((theta - 30) / 45)
    f2 = clamp((R - 0.6) / 0.6)
    f3 = clamp(1 - still / MOTION_REF)
    score = 0.50 * f1 + 0.35 * f2 + 0.15 * f3

    return Frame(theta, R, hh, hip_h, knee, neck_deg, still, drop, omega,
                 grounded, seated, score)


# ============================ 状态机 ============================
class FallJudge:
    """三通道判决：A 冲击型 / B 静默型 / C 坐位垂头型 → SUSPECT → ALERT。"""

    def __init__(self):
        self.alert_t = -1e9          # 最近一次告警时刻（跨事件保留，用于重复告警抑制）
        self.alert_start = None
        self.reset()

    def reset(self):
        self.state, self.kind, self.t0 = "NORMAL", None, 0.0
        self.t_lost = None
        self.impact_t = -1e9
        self.cross_t = -1e9
        self.c_start = None
        self.streak = 0

    def step(self, f, hh_prev, now):
        """输入当前帧特征，返回触发告警的通道（仅跃迁到 ALERT 的那一帧非 None）。"""
        # --- 事件锁存：冲击 / 跨越贴地线 / 坐位垂头起点 ---
        if f.drop > IMPACT_DROP or f.omega > IMPACT_OMEGA:
            self.impact_t = now
        if hh_prev is not None and hh_prev >= HH_GROUND > f.hh:
            self.cross_t = now
        c_ok = (ENABLE_C and f.seated and f.neck >= SEAT_ANGLE
                and f.still <= STILL_LIMIT)
        if c_ok:
            if self.c_start is None:
                self.c_start = now
        else:
            self.c_start = None

        if self.state == "NORMAL":
            kind, t_ev = None, 0.0
            if f.grounded and now - self.impact_t <= IMPACT_WINDOW:
                kind, t_ev = "A", self.impact_t
            elif f.grounded and now - self.cross_t <= CROSS_WINDOW:
                kind, t_ev = "B", self.cross_t
            elif c_ok:
                kind, t_ev = "C", self.c_start
            # C 不参与姿态评分：坐位垂头时 R/θ 都不显著
            gate = kind is not None and (kind == "C" or f.score >= SCORE_TH)
            if gate and now - self.alert_t >= ALERT_COOLDOWN:
                self.streak += 1
                if self.streak >= DEBOUNCE_FRAMES:
                    self.state, self.kind, self.t0 = "SUSPECT", kind, t_ev
                    self.t_lost, self.streak = None, 0
            else:
                self.streak = 0

        elif self.state == "SUSPECT":
            if self.kind == "C":
                hold = f.seated and f.neck >= SEAT_ANGLE - 10 and f.still <= STILL_HOLD
                limit = SEAT_CONFIRM
            else:
                hold = f.grounded
                limit = CONFIRM_SEC
            if hold:
                self.t_lost = None
                if now - self.t0 >= limit:          # 观察窗满 → 确认
                    self.state, self.alert_t = "ALERT", now
                    self.alert_start = now
                    return self.kind
            else:
                if self.t_lost is None:
                    self.t_lost = now
                elif now - self.t_lost > REVOKE_SEC:   # 恢复 3 s → 撤销
                    self.state, self.kind, self.t_lost = "NORMAL", None, None

        elif self.state == "ALERT":
            recovered = f.hh >= HH_GROUND and f.neck < SEAT_ANGLE - 15
            if recovered:                              # 事件闭环，复位
                self.state, self.kind = "NORMAL", None
                self.alert_t = -1e9                    # 新事件可立即上报
            elif now - (self.alert_start or now) > ALERT_TIMEOUT:
                self.state, self.kind = "NORMAL", None  # 超时复位，保留抑制窗口
        return None


# ============================ 告警 ============================
KIND_NAME = {"A": "跌倒（冲击型）", "B": "晕倒（缓慢滑落）",
             "C": "晕厥（坐位垂头）", "S": "久蹲提醒（请确认）"}


class Pusher:
    """后台线程推送，避免网络阻塞视频循环。"""

    def __init__(self, webhook):
        self.webhook = webhook
        self.q = queue.Queue(maxsize=32)
        threading.Thread(target=self._run, daemon=True).start()

    def send(self, kind, hold, score, area, eid, image_path):
        name = KIND_NAME.get(kind, "异常")
        if kind == "S":                    # 久蹲旁路提醒：不带事件编号与置信度
            text = ("### 【%s】%s\n- 时间：%s\n- 提示：已保持低位姿势约 %.0f 分钟，"
                    "请确认老人状态；如需起身请缓慢，防止体位性低血压。"
                    % (name, area, datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                       hold / 60.0))
        else:
            text = ("### 【%s】%s\n"
                    "- 事件编号：%s\n- 区域：%s\n- 时间：%s\n"
                    "- 持续：%.0f s\n- 置信度：%.2f\n- 骨架图：%s"
                    % (name, area, eid, area,
                       datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                       hold, score, self._image_ref(image_path)))
        try:
            self.q.put_nowait(text)
        except queue.Full:
            print("[warn] 告警队列已满，丢弃一条")

    @staticmethod
    def _image_ref(path):
        if not path:
            return "（未生成）"
        if PUBLIC_IMAGE_BASE:
            return "![骨架](%s/%s)" % (PUBLIC_IMAGE_BASE.rstrip("/"), os.path.basename(path))
        return "已保存至本机 %s" % path

    def _run(self):
        while True:
            text = self.q.get()
            if not self.webhook:
                print("[info] 未配置 WEBHOOK，告警内容如下：\n" + text)
                continue
            for attempt in (1, 2):
                try:
                    resp = requests.post(
                        self.webhook,
                        json={"msgtype": "markdown",
                              "markdown": {"title": "跌倒告警", "text": text}},
                        timeout=5)
                    data = resp.json() if resp.ok else {}
                    if data.get("errcode", 0) == 0:
                        break
                    print("[warn] 推送被拒：%s %s" % (resp.status_code, resp.text[:200]))
                except Exception as exc:          # 网络异常：重试一次
                    print("[warn] 推送失败(%d/2)：%s" % (attempt, exc))
                    time.sleep(1)


def _draw(pts, size):
    """在纯黑画布上绘制骨架（points 为 0—1 归一化坐标）。"""
    w, h = size
    canvas = np.zeros((h, w, 3), np.uint8)
    pix = [(int(x * w), int(y * h)) for x, y in pts]
    for a, b in mp_pose.POSE_CONNECTIONS:
        cv2.line(canvas, pix[a], pix[b], (0, 210, 190), 2, cv2.LINE_AA)
    for p in pix:
        cv2.circle(canvas, p, 3, (60, 120, 250), -1, cv2.LINE_AA)
    return canvas


def save_skeleton(lm, path, size=(480, 360)):
    """纯骨架图：只含关键点与骨骼连线，不含任何原始像素。"""
    canvas = _draw([(p.x, p.y) for p in lm], size)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    cv2.imwrite(path, canvas)
    return path


def render_preview(lm, size):
    """本地预览：骨架（尺寸与原始帧一致），状态文字由调用方叠加。"""
    return _draw([(p.x, p.y) for p in lm], size)


# ============================== 主流程 ==============================
def open_capture(src, retries=5):
    for i in range(retries):
        cap = cv2.VideoCapture(src)
        if cap.isOpened():
            return cap
        cap.release()
        time.sleep(min(2 ** i, 8))
    return None


def is_live_source(src):
    """本机摄像头或网络流才需要断线重连；本地视频文件读到结尾就应退出。"""
    return isinstance(src, int) or str(src).startswith(
        ("rtsp://", "rtmp://", "http://", "https://"))


def main():
    cap = open_capture(VIDEO_SOURCE)
    if cap is None:
        raise SystemExit("无法打开视频源：%r" % (VIDEO_SOURCE,))

    judge = FallJudge()
    pusher = Pusher(DINGTALK_WEBHOOK)
    hist = collections.deque(maxlen=SMOOTH_N)
    l_ref, calib = None, []
    calib_deadline = None
    prev_sm, prev_hh, prev_theta, t_prev = None, None, None, None
    lost_since, squat_t0, seq = None, None, 0

    try:
        while True:
            ok, frame = cap.read()
            if not ok:                      # 视频源断开：实时流重连，文件直接结束
                if not is_live_source(VIDEO_SOURCE):
                    break
                print("[warn] 读取失败，尝试重连…")
                cap.release()
                cap = open_capture(VIDEO_SOURCE)
                if cap is None:
                    break
                continue

            now = time.monotonic()
            frame = cv2.flip(frame, 1)
            h, w = frame.shape[:2]
            res = POSE.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))

            if not res.pose_landmarks:
                lost_since = lost_since or now
                hist.clear()
                prev_sm, prev_hh, prev_theta = None, None, None
                if now - lost_since > LOST_RESET_SEC:
                    judge.reset()
                cv2.imshow("skeleton", np.zeros((360, 480, 3), np.uint8))
                if cv2.waitKey(1) & 0xFF in (27, ord("q")):
                    break
                continue
            lost_since = None

            lm = res.pose_landmarks.landmark
            hist.append([(p.x, p.y, getattr(p, "visibility", 1.0)) for p in lm])
            sm = [Pt(sum(q[i][0] for q in hist) / len(hist),
                     sum(q[i][1] for q in hist) / len(hist),
                     sum(q[i][2] for q in hist) / len(hist)) for i in range(33)]

            dt = (now - t_prev) if t_prev else 1.0 / FPS_FALLBACK
            dt = min(max(dt, 1e-3), 1.0)

            if l_ref is None:               # 开机标定：躯干长中位数
                if calib_deadline is None:
                    calib_deadline = now + CALIB_SEC
                t_px = torso_px(sm, w, h)
                if t_px > 1:
                    calib.append(t_px)
                if len(calib) >= CALIB_FRAMES or now > calib_deadline:
                    l_ref = median(calib) if calib else 0.18 * h
                    print("[info] 尺度基准 L_ref = %.1f px（样本 %d）"
                          % (l_ref, len(calib)))
                prev_sm, prev_hh, prev_theta, t_prev = sm, None, None, now
                cv2.imshow("skeleton", render_preview(sm, (w, h)))
                if cv2.waitKey(1) & 0xFF in (27, ord("q")):
                    break
                continue

            f = posture_features(sm, w, h, l_ref, prev_sm, dt, prev_hh, prev_theta)
            hit = judge.step(f, prev_hh, now)
            prev_sm, prev_hh, prev_theta, t_prev = sm, f.hh, f.theta, now

            if hit:
                seq += 1
                eid = "F%s-%03d" % (datetime.now().strftime("%Y%m%d-%H%M"), seq)
                path = os.path.join(ALERT_DIR, eid + ".jpg")
                try:
                    save_skeleton(sm, path, size=(int(round(360.0 * w / h)), 360))
                except Exception as exc:
                    print("[warn] 骨架图保存失败：", exc)
                    path = ""
                pusher.send(hit, now - judge.t0, f.score, AREA, eid, path)

            # 久蹲旁路提醒：低姿态久留但未构成跌倒
            if judge.state == "NORMAL" and f.hip_h < HIP_SEATED_MAX and f.hh < SEATED_HH_MAX:
                squat_t0 = squat_t0 or now
                if now - squat_t0 > SQUAT_REMIND_SEC:
                    pusher.send("S", SQUAT_REMIND_SEC, 1.0, AREA, "久蹲提醒", "")
                    squat_t0 = now
            else:
                squat_t0 = None

            vis = render_preview(sm, (w, h))
            cv2.putText(vis, "%s %s S=%.2f hh=%.2f hip=%.2f neck=%.0f v=%.2f"
                        % (judge.state, judge.kind or "-", f.score, f.hh,
                           f.hip_h, f.neck, f.still),
                        (10, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.55,
                        (255, 255, 255), 2)
            cv2.imshow("skeleton", vis)
            if cv2.waitKey(1) & 0xFF in (27, ord("q")):
                break
    finally:
        cap.release()
        cv2.destroyAllWindows()
        POSE.close()


if __name__ == "__main__":
    main()
