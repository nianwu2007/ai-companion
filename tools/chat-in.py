# -*- coding: utf-8 -*-
"""小怜的笔：不打字也能在 Minecraft 里说中文。

双击 chat-in.bat 打开这个常驻小窗，输入中文按回车，话就直接发进游戏聊天。
走 maidai_bridge 的本机桥 http://127.0.0.1:8790/chat，只连 127.0.0.1，不出网。

快捷键：
    回车        发送
    上 / 下     翻历史
    Esc         清空输入框
    Ctrl+H      收起窗口
"""
import json
import tkinter as tk
import urllib.error
import urllib.request

BRIDGE = "http://127.0.0.1:8790/chat"
TIMEOUT = 5.0
OK = "#2f8f4e"
BAD = "#b03535"
IDLE = "#777777"


def send(text):
    payload = json.dumps({"text": text}).encode("utf-8")
    req = urllib.request.Request(
        BRIDGE, data=payload, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.status, resp.read().decode("utf-8", "replace")


class ChatIn:
    def __init__(self, root):
        self.root = root
        self.history = []
        self.cursor = 0

        root.title("小怜的笔 · 中文直达游戏")
        root.attributes("-topmost", True)
        try:
            root.attributes("-alpha", 0.94)
        except tk.TclError:
            pass

        w, h = 560, 108
        x = (root.winfo_screenwidth() - w) // 2
        root.geometry("%dx%d+%d+%d" % (w, h, x, 24))
        root.configure(bg="#1b1b20")

        tk.Label(
            root, text="写一句话，回车就送进游戏聊天", bg="#1b1b20",
            fg="#c9c9d1", font=("Microsoft YaHei UI", 10),
        ).pack(anchor="w", padx=12, pady=(8, 0))

        self.entry = tk.Entry(
            root, font=("Microsoft YaHei UI", 14), bg="#2a2a33", fg="#f2f2f5",
            insertbackground="#f2f2f5", relief="flat",
        )
        self.entry.pack(fill="x", padx=12, pady=(4, 0), ipady=6)
        self.entry.focus_force()

        self.status = tk.Label(
            root, text="就绪", bg="#1b1b20", fg=IDLE,
            font=("Microsoft YaHei UI", 9), anchor="w",
        )
        self.status.pack(fill="x", padx=12, pady=(4, 6))

        self.entry.bind("<Return>", self.on_send)
        self.entry.bind("<Up>", self.on_up)
        self.entry.bind("<Down>", self.on_down)
        self.entry.bind("<Escape>", lambda e: self.entry.delete(0, "end"))
        root.bind("<Control-h>", lambda e: root.iconify())

    def say(self, text, color):
        self.status.configure(text=text, fg=color)

    def on_send(self, _event=None):
        text = self.entry.get().strip()
        if not text:
            return "break"
        try:
            code, body = send(text)
        except urllib.error.URLError as exc:
            self.say("桥没通，游戏开了吗？  %s" % exc, BAD)
            return "break"
        except Exception as exc:  # noqa: BLE001
            self.say("发送失败：%s" % exc, BAD)
            return "break"

        if code == 200:
            self.history.append(text)
            self.cursor = len(self.history)
            self.entry.delete(0, "end")
            head = text if len(text) <= 30 else text[:30] + "…"
            self.say("已发送  %s" % head, OK)
        else:
            self.say("桥回 %s  %s" % (code, body[:60]), BAD)
        return "break"

    def on_up(self, _event=None):
        if self.history and self.cursor > 0:
            self.cursor -= 1
            self.entry.delete(0, "end")
            self.entry.insert(0, self.history[self.cursor])
        return "break"

    def on_down(self, _event=None):
        if self.history and self.cursor < len(self.history) - 1:
            self.cursor += 1
            self.entry.delete(0, "end")
            self.entry.insert(0, self.history[self.cursor])
        else:
            self.cursor = len(self.history)
            self.entry.delete(0, "end")
        return "break"


def main():
    root = tk.Tk()
    ChatIn(root)
    root.mainloop()


if __name__ == "__main__":
    main()
