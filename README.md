<div align="center">

<img src="src/MoonInternet.App/Assets/moon.png" width="96" alt="">

# Moon Internet

Клиент для VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и WireGuard.
Windows и Android.

[![Версия](https://img.shields.io/github/v/release/YounikarYounhard/MoonInternet?include_prereleases&label=%D0%B2%D0%B5%D1%80%D1%81%D0%B8%D1%8F&color=9D7BFF)](../../releases/latest)
[![MIT](https://img.shields.io/badge/%D0%BB%D0%B8%D1%86%D0%B5%D0%BD%D0%B7%D0%B8%D1%8F-MIT-blue)](LICENSE)

[![Скачать для Windows](https://img.shields.io/badge/%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C_%D0%B4%D0%BB%D1%8F-Windows-9D7BFF?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/YounikarYounhard/MoonInternet/releases/latest/download/MoonInternet-Setup.exe)
[![Скачать для Android](https://img.shields.io/badge/%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C_%D0%B4%D0%BB%D1%8F-Android-9D7BFF?style=for-the-badge&logo=android&logoColor=white)](https://github.com/YounikarYounhard/MoonInternet/releases/latest/download/MoonInternet.apk)

[Все выпуски](../../releases) · [English](README-EN.md)

</div>

---

Обычный клиент: вставляете ссылку-подписку, получаете список серверов, жмёте на луну.
Аккаунтов нет, регистрации нет, серверов у нас тоже нет — они ваши.

Версия **0.9.2 beta**. Пользоваться можно каждый день, но шероховатости попадаются.
Интерфейс на русском и английском, переключается на лету.

| | | |
|---|---|---|
| **Windows** | 10/11, x64 | `MoonInternet-Setup.exe` |
| **Android** | 7.0+, arm64 | `MoonInternet.apk` |

## Что умеет

Подписки обновляются сами — по тому интервалу, который присылает панель, а не по выдуманному.
Список серверов и ключи лежат в кэше, поэтому серверы видно и без интернета.
Трафик и срок действия показываются так, как удобнее: цифрами, полосой или точками.
Когда трафика остаётся меньше десяти процентов или подписка подходит к концу — приложение скажет.

Маршрутизация — Direct, Proxy и Block по доменам, IP и тегам `geosite:` / `geoip:`.
Профиль можно взять из HAPP или INCY, а можно собрать свой, с поиском по гео-базе.
На обеих платформах есть раздельный туннель: выбранные приложения мимо VPN — или, наоборот,
только они через него.

Пинг честный. Обычные методы отвечают ровно на один вопрос — слушает ли кто-нибудь порт,
а это совсем не то же самое, что «протокол работает»: CDN, промежуточный сервер провайдера
или протухший ключ рукопожатие тоже завершат, и мёртвый сервер покажет бодрые 30 мс.
Поэтому есть метод «Стабильность»: он поднимает настоящее соединение и делает через него
запрос. Медленнее остальных, зато не врёт.

Обновления приходят с GitHub. Кнопка «Скачать» не открывает браузер, а качает установщик
и запускает его; скачанный файл удаляется при следующем старте.

## Установка

Windows — запустите установщик из [релизов](../../releases/latest). Он поставит приложение
в Program Files, зарегистрирует служебный компонент для TUN и создаст ярлыки. Ставить .NET
отдельно не нужно, он внутри — отсюда и размер.

Android — обычный APK оттуда же. Встаёт поверх предыдущей версии, данные сохраняются.

Сборки не подписаны сертификатом, поэтому Windows покажет SmartScreen
(*Подробнее → Выполнить в любом случае*), а пара антивирусных движков из шестидесяти могут
поругаться на эвристику: неподписанное приложение, которое несёт VPN-ядра и правит таблицу
маршрутизации, выглядит для них подозрительно. Если это смущает — соберите из исходников,
получится ровно то же самое.

## Сборка

```powershell
git clone https://github.com/YounikarYounhard/MoonInternet.git
cd MoonInternet

# ядра в репозитории не лежат — это ~120 МБ чужих бинарников
powershell -ExecutionPolicy Bypass -File build\get-cores.ps1

dotnet publish src\MoonInternet.App        -c Release -r win-x64 --self-contained true -o dist\app
dotnet publish src\MoonInternet.TunService -c Release -r win-x64 --self-contained true -o dist\app

# ядра ищутся рядом с exe, а установщик пакует только dist\app
Copy-Item cores dist\app\cores -Recurse -Force

makensis build\installer.nsi   # если нужен установщик
```

Нужен .NET 9 SDK. Для Android — `android\build-xray.ps1` (скачает Go, если его нет),
затем `gradlew assembleRelease`; понадобятся Android SDK, NDK и JDK 17, путь к ним задаётся
переменной `MOON_TOOLCHAIN`. Собирается только под arm64.

## Как устроено

Протоколы мы не переписываем. На C# и Kotlin написана оркестрация — разбор ссылок и подписок,
генерация конфигов, маршрутизация, интерфейс, — а трафик обрабатывают проверенные ядра:
xray-core, sing-box и tun2socks. Так же сделаны Happ, Nekoray и v2rayN.

```
src/MoonInternet.App          интерфейс WPF
src/MoonInternet.Core         модели, парсеры, генераторы конфигов
src/MoonInternet.Services     подключение, ядра, подписки, пинг, гео
src/MoonInternet.TunService   служебный компонент (SYSTEM) — TUN-адаптер и маршруты

android/app/.../core          порт Core
android/app/.../data          подписки, хранилище, гео
android/app/.../vpn           VpnService, xray, плитка в шторке
android/app/.../ui            экраны на Compose
```

Отдельный служебный компонент на Windows нужен по скучной причине: чтобы поднять TUN, надо
создать сетевой адаптер и поправить таблицу маршрутизации, а это невозможно без повышения прав.
Компонент работает как задача от имени SYSTEM, поэтому само приложение остаётся обычным
и UAC не спрашивает разрешение при каждом запуске. Слушает он только `127.0.0.1:35555`
и понимает ровно свой небольшой набор команд.

На Android ничего этого не требуется: `VpnService` отдаёт дескриптор туннеля, и он уходит
прямо во встроенный TUN-инбаунд xray.

## Про данные

Аккаунтов нет, телеметрии нет, «домой» приложение не ходит. Подписки, серверы и ключи лежат
только на устройстве: в папке `save\` рядом с приложением на Windows и во внутреннем хранилище
на Android. Наружу приложение обращается ровно к двум типам адресов — к вашим ссылкам-подпискам
и к источникам гео-правил на GitHub, и то лишь когда включена маршрутизация. Логи локальные.

## Лицензии

Код Moon Internet — [MIT](LICENSE). Ядра и библиотеки идут под своими лицензиями, список
в [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md); sing-box и tun2socks под GPL-3.0
и поставляются без изменений.

---

*Это прокси-инструмент, а не VPN-сервис: серверы вы добавляете свои. За то, как вы его
используете, и за соблюдение законов своей страны отвечаете вы.*
