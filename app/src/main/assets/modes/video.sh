#!/system/bin/sh
# 切到**视频模式**: 整个切换就这一条命令 (应用那一侧一次做完)
#
# 三个模式各有一个这样的脚本 (phone.sh / video.sh / screen.sh), 切模式时**只跑对应的那一个**:
# 提示词换掉、摄像头开起来、常驻语音留上, 三件事都在 `mode` 那一次桥调用里 —— 这边不拼第二条命令,
# 也不等谁开完 (相机那半在应用里后台进行, 开好了 `lw_look` 直接用)。切换快不快就差在这里
#
# 视频模式是什么: 用手机自己的摄像头取帧识图 (相机开在应用进程里, 预览是一块悬浮小窗), 识别链常驻
# (说话不用再喊唤醒词)。收工走 phone.sh, 去看屏幕走 screen.sh
#
# 用法: video.sh
#   回执四行: mode / camera / voice / detail —— 相机那一行说的是"已经开着"还是"正在开"
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

# 键是 true/false 的那些值
#
# **不写 sed 的 `\|` 交替**: 设备上是 toybox 的 sed, 它的 BRE 不认 `\|` —— 那种规则永远匹配不上, 于是
# 所有成功路径都会被当成失败 (2026-10-05 就是这么"看起来能跑"的)。glob 匹配不依赖任何外部工具
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

answer=$(ask mode ',"mode":"video"')
if [ "$(bool_field "$answer" ok)" = "true" ] && [ "$(bool_field "$answer" switched)" = "true" ]; then
  echo "mode -> $(str_field "$answer" mode) ($(str_field "$answer" name))"
  echo "camera: $(str_field "$answer" camera)"
  echo "voice: $(str_field "$answer" voice)"
  echo "$(str_field "$answer" detail)"
else
  report_failure 'could not switch to video mode' "$answer"
fi
