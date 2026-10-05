#!/system/bin/sh
# 打开常驻语音输入 (batch 1/2 那条链: 一个 AudioRecord + silero VAD + 端侧 SenseVoice)
#
# 视频模式是"说话为主"的 —— 进这个模式必须把这条链开上, 不然说了没人听。
# 真正的开关在应用那一侧 (WakeWordService), 所以这个脚本做两件事:
#   1. 留下"要它开着"的记号, 宿主插件看到记号会走桥把它起起来 (这一步不需要特权)
#   2. 顺便把状态打出来, 让人一眼看出现在是不是真的在听
#
# 用法: voice-input.sh on | off | status
D=/data/data/io.github.miuzarte.littlewhale/files/dsh-home
MARK="$D/modes/voice-input.on"
case "$1" in
  off)
    rm -f "$MARK"
    echo "voice input: off (asked for; the app stops the chain on its next check)"
    ;;
  status)
    if [ -f "$MARK" ]; then echo "voice input: asked for (marker present)"; else echo "voice input: not asked for"; fi
    ;;
  on|"")
    mkdir -p "$D/modes"
    date > "$MARK"
    echo "voice input: on (marker written; the host starts the always-listening chain)"
    ;;
  *)
    echo "usage: voice-input.sh on | off | status"
    exit 1
    ;;
esac
