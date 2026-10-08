#!/system/bin/sh
# The running host's environment, which is where the privileged channel's endpoint and token live
#
# Pushed to the device by the PowerShell scripts next to it; the app's data directory is only
# readable as root, so this is run through `su`
#
# 用法: su -c 'sh /data/local/tmp/lw-host-env.sh'   (或 run-as, 直接跑也行) —— 打出
#       LW_CHANNEL_ENDPOINT / LW_CHANNEL_TOKEN 两行, 宿主没在跑就退出码 1
pid=$(ps -A -o PID,ARGS | grep 'bin.js web' | grep -v grep | awk '{print $1}' | head -1)
if [ -z "$pid" ]; then
  echo "no host process" >&2
  exit 1
fi
tr '\0' '\n' < "/proc/$pid/environ"
