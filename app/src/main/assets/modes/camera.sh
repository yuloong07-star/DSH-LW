#!/system/bin/sh
# 换镜头: 前摄 / 后摄 (从 shell 里切一次, 不花模型一轮)
#
# 为什么一个脚本也能做这件事: 应用那一侧的桥是一个回环 TCP, 一连接一请求一行 JSON, 而宿主进程的环境
# 里就带着它的地址与 token (同一个 uid 读得到 /proc/<pid>/environ)。所以这里把 `lw_look {lens:...}` 那条路
# 照走一遍 —— 模型用工具切, 人 (或别的脚本) 用这一条切, 切的是同一台相机
#
# 相机的口径, 三条都要知道:
#   1. 前后摄是**两个设备**: 换一头只能把旧的还回去再开新的, 所以切一次就是一次开关 (几百毫秒),
#      而且系统相机应用此刻打不开、反过来也一样 (相机是独占的)
#   2. 点名要的那一头设备上没有 (比如模拟器只有后摄) 时, 桥回一句
#      "no camera on this device faces front" —— 这里照原话念出来, **不拿另一头顶上**
#   3. 镜头是**粘的**: 切过去之后模型下一次取景还在那一头, 直到有人说要换 (所以 off 之后回到后摄)
#
# 用法: camera.sh front | back | status | off
#   front / back  切到那一头 (已经就是它时什么都不做, 只回现状)
#   status        现在哪一头、开着没有、尺寸多少
#   off           把摄像头还回去 (连这一趟抓的帧文件一起删掉)
set -u

find_channel() {
  for d in /proc/[0-9]*; do
    [ -r "$d/environ" ] || continue
    env=$(tr '\0' '\n' < "$d/environ" 2>/dev/null)
    endpoint=$(printf '%s\n' "$env" | sed -n 's/^LW_CHANNEL_ENDPOINT=//p' | head -1)
    [ -n "$endpoint" ] || continue
    token=$(printf '%s\n' "$env" | sed -n 's/^LW_CHANNEL_TOKEN=//p' | head -1)
    [ -n "$token" ] || continue
    host=${endpoint%:*}
    port=${endpoint##*:}
    return 0
  done
  return 1
}

# 一次连接一次请求: extra 是拼在 token 后面的键值片段 (调用方自己带逗号与引号)
ask() {
  printf '{"method":"%s","token":"%s"%s}\n' "$1" "$token" "${2:-}" | nc "$host" "$port" 2>/dev/null | head -1
}

# 键是字符串的那些值 (句子里的逗号与括号都留着)
str_field() {
  printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p" | head -1
}

# 键是数字的那些值
num_field() {
  printf '%s' "$1" | sed -n "s/.*\"$2\":\([0-9][0-9]*\).*/\1/p" | head -1
}

# 键是 true/false 的那些值
#
# **不写 sed 的 `\|` 交替**: 设备上是 toybox 的 sed, 它的 BRE 不认 `\|` —— 那种规则永远匹配不上, 于是
# 所有成功路径都会被当成失败 (2026-10-05 就是这么"看起来能跑"的: front 那句真话其实来自失败分支, 而
# status/back 的成功回包被丢掉了)。glob 匹配不依赖任何外部工具
bool_field() {
  case "$1" in
    *"\"$2\":true"*) echo true ;;
    *"\"$2\":false"*) echo false ;;
  esac
}

# 失败就说清楚: 回包里没有 error 字段时把原样打出来 —— 静默回一句"失败了"是最难查的那种
report_failure() {
  detail=$(str_field "$2" error)
  if [ -n "$detail" ]; then
    echo "$1: $detail"
  else
    echo "$1: the app answered something this script could not read: $(printf '%s' "$2" | cut -c1-200)"
  fi
  exit 1
}

find_channel || {
  echo "no channel: the host is not running (LW_CHANNEL_ENDPOINT is in no process)"
  exit 1
}

case "${1:-status}" in
  front|back)
    answer=$(ask camera ",\"op\":\"open\",\"lens\":\"$1\"")
    if [ "$(bool_field "$answer" ok)" = "true" ]; then
      echo "camera: now on the $(str_field "$answer" lens) lens, $(str_field "$answer" shot) stills, window $(bool_field "$answer" window)"
      echo "$(str_field "$answer" text)"
    else
      # 真话: 设备上没有那一头就是没有, 不退回另一头
      report_failure "could not switch to the $1 camera" "$answer"
    fi
    ;;
  off)
    answer=$(ask camera ',"op":"close","clean":true')
    if [ "$(bool_field "$answer" ok)" = "true" ]; then
      echo "camera: $(str_field "$answer" text)"
    else
      report_failure 'could not put the camera away' "$answer"
    fi
    ;;
  status)
    answer=$(ask camera ',"op":"status"')
    if [ "$(bool_field "$answer" ok)" != "true" ]; then
      report_failure 'could not read the camera' "$answer"
    fi
    echo "camera: $(bool_field "$answer" open) (open) / lens $(str_field "$answer" lens) / $(num_field "$answer" cameras) camera(s) on this device"
    echo "$(str_field "$answer" text)"
    ;;
  *)
    echo "usage: camera.sh front | back | status | off"
    exit 1
    ;;
esac
