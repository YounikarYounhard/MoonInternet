# Ядра Moon Internet

Эта ветка — не код. Здесь лежат движки, которые приложение докачивает после установки,
и каталог, по которому оно решает, что скачать и что обновить.

Своя ветка, а не `master`, по двум причинам: движки весят мегабайты и меняются по своему
расписанию, поэтому ядро можно перевыпустить, не трогая приложение, а история приложения не
таскает за собой копию каждого ядра, которое когда-либо выходило.

| Файл | Что это |
|---|---|
| `android-arm64.json` | каталог для телефона |
| `windows-x64.json` | каталог для ПК |
| `libawgproxy.so` | AmneziaWG для Android, arm64 |
| `awg.zip` | AmneziaWG для Windows x64 |

Приложение сверяет скачанное с `sha256` из каталога **до** того, как загрузит его. Поэтому
файл и каталог нельзя обновлять по отдельности: собирает их вместе `build/pack-cores.ps1`
на `master`, он же прописывает размер и хеш.

Обновить ядро:

```
powershell -ExecutionPolicy Bypass -File build\build-awgproxy.ps1 -Android
powershell -ExecutionPolicy Bypass -File build\build-awgproxy.ps1
powershell -ExecutionPolicy Bypass -File build\pack-cores.ps1
```

затем положить сюда `dist\cores\*` вместе с обоими каталогами из `cores-catalog\`.
