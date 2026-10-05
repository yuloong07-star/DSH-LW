#!/system/bin/sh
# 只在测试时用: 找出 host 进程环境里的通道地址与 token (与别的 app 无关)
for pid in $(ls /proc | grep '^[0-9][0-9]*$'); do
  cmd=$(tr '\0' ' ' < /proc/$pid/cmdline 2>/dev/null)
  case "$cmd" in
    *node* | *dsh*)
      found=$(tr '\0' '\n' < /proc/$pid/environ 2>/dev/null | grep '^LW_CHANNEL_')
      if [ -n "$found" ]; then
        echo "PID=$pid"
        echo "$found"
      fi
      ;;
  esac
done
