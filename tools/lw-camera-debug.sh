#!/system/bin/sh
# 临时探针: 把 camera.sh 那条 ask 的**原始回包**打出来 (排"设备侧脚本收不到答案"这件事)
#
# 用法: 推上设备后 sh lw-camera-debug.sh —— 它自己从 /proc/<pid>/environ 找 endpoint 与 token,
#       然后逐条打 status / front / status -w 5, 也就是设备侧脚本看到的那几行原始回包
set -u

endpoint=""
token=""
for d in /proc/[0-9]*; do
  [ -r "$d/environ" ] || continue
  env=$(tr '\0' '\n' < "$d/environ" 2>/dev/null)
  endpoint=$(printf '%s\n' "$env" | sed -n 's/^LW_CHANNEL_ENDPOINT=//p' | head -1)
  [ -n "$endpoint" ] || continue
  token=$(printf '%s\n' "$env" | sed -n 's/^LW_CHANNEL_TOKEN=//p' | head -1)
  [ -n "$token" ] || continue
  break
done
host=${endpoint%:*}
port=${endpoint##*:}
echo "endpoint=$endpoint  nc=$(command -v nc)"

probe() { # $1 标签, $2 params 片段, 其余 nc 旗标
  label=$1
  params=$2
  shift 2
  printf '%s\n' "--- $label ---"
  printf '{"method":"camera","token":"%s"%s}\n' "$token" "$params" | nc "$@" "$host" "$port"
  printf '[nc exit=%s]\n' "$?"
}

probe 'status' ',"op":"status"'
probe 'front' ',"op":"open","lens":"front"'
probe 'status -w 5' ',"op":"status"' -w 5
printf '%s\n' '--- status | head -1 (脚本里的写法) ---'
printf '{"method":"camera","token":"%s","op":"status"}\n' "$token" | nc "$host" "$port" | head -1
printf '[pipeline exit=%s]\n' "$?"
