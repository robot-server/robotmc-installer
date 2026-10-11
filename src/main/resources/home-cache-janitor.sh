#!/bin/sh
# 이 프로세스가 끝난 뒤에 홈 캐시만 지운다. 심볼릭 링크의 바깥 대상은 지우지 않는다.
set -u
pid=$1
home=$2
name=$(basename -- "$home")
if [ "$name" != ".robotmc-installer" ]; then
  exit 1
fi
user_home=${HOME-}
if [ "$home" = "/" ] || { [ -n "$user_home" ] && [ "$home" = "$user_home" ]; }; then
  exit 1
fi
while kill -0 "$pid" 2>/dev/null; do
  sleep 0.2
done
sleep 0.3
if [ -L "$home" ]; then
  rm -f -- "$home"
else
  if [ -d "$home" ]; then
    find -P "$home" -type l -exec rm -f -- {} \;
  fi
  rm -rf -- "$home"
fi
script=$0
rm -f -- "$script"
rmdir "$(dirname -- "$script")" 2>/dev/null || true
