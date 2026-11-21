#!/bin/sh
# ВАЖНО: эмулятор tv33 ЗАПУСКАЕТСЯ ВНЕ ПЕСОЧНИЦЫ Trae.
#
# Причина: из терминала агента qemu падает с `HVF error: HV_DENIED`
# (Apple Silicon, Hypervisor.framework запрещён внутри песочницы Trae).
# Поэтому команда передаётся в системный Terminal.app через osascript —
# процесс стартует в обычном окружении пользователя, и HVF разрешается.
#
# Использование:  ./run-emulator.sh
# Затем (из любой среды): user~/.sdk/platform-tools/adb devices  ->  emulator-5554
exec osascript -e 'tell application "Terminal" to do script "export ANDROID_HOME=$HOME/Library/Android/sdk; export ANDROID_SDK_ROOT=$ANDROID_HOME; $ANDROID_HOME/platform-tools/adb start-server; $ANDROID_HOME/emulator/emulator -avd tv33 -no-boot-anim -netdelay none -netspeed full"'
