<div align="center">

# 🐝 Жужалка

**Простой VPN-клиент для Android. Для себя и друзей.**

Шесть бесплатных списков · свои подписки · автоподбор

[**Скачать APK для Android**](https://github.com/UserOfTheWorld-code/zhuzhalka/releases/tag/v0.1.0-beta.1)
&nbsp; · &nbsp;
[Как пользоваться](#как-пользоваться)
&nbsp; · &nbsp;
[Исходники и сборка](#для-разработчиков)

Android 7.0+ · Kotlin / Compose · GPL-3.0 · версия 0.1.0 beta

</div>

<p align="center">
  <img src="docs/main.png" width="280" alt="Главный экран Жужалки: подключение и автоподбор" />
  &nbsp;
  <img src="docs/sources.png" width="280" alt="Шесть бесплатных источников и собственная подписка" />
</p>

## Установка

1. Откройте [релиз 0.1.0 beta](https://github.com/UserOfTheWorld-code/zhuzhalka/releases/tag/v0.1.0-beta.1).
2. Скачайте **Zhuzhalka_0.1.0_universal.apk** — он подходит для всех поддерживаемых архитектур.
   Для большинства современных телефонов также подойдёт меньший `arm64-v8a.apk`.
3. Откройте APK на телефоне и разрешите установку из выбранного браузера или файлового менеджера.
4. Запустите «Жужалку». При первом подключении Android попросит разрешение на VPN.

Это первая beta-сборка с debug-подписью для личного тестирования. Установка бесплатна,
регистрация в приложении не нужна. Файлы APK и контрольные суммы находятся в релизе.

## Как пользоваться

- **Кнопка списка** загружает источник при первом нажатии, затем открывает сохранённые серверы.
- **Обновить** получает свежий список. У каждого источника и своей подписки есть отдельная кнопка.
- **Автоподбор** обновляет источники, чередует их серверы и ищет вариант, прошедший три веб-проверки.
- **Подключить / Отключить** управляет VPN. Для автоподбора сначала отключитесь.
- **Моя подписка** принимает HTTPS-ссылку совместимого V2Ray-провайдера.
- **Ещё** открывает расширенные настройки v2rayNG.

Автоподбор проверяет HTTPS-доступ к `web.whatsapp.com`, `web.telegram.org` и `x.com`
через каждый проверяемый профиль. Это проверка веб-доступа: она не доказывает работу сообщений,
входа и звонков. Поиск занимает около трёх минут после обновления списков; текущий запрос
может завершиться позже. Неудачный поиск можно повторить — порядок серверов изменится.

## Бесплатные источники

| Кнопка | Публикатор |
|---|---|
| Au1rxx | [Free VPN Subscriptions](https://github.com/Au1rxx/free-vpn-subscriptions) |
| Eternity | [V2RayAggregator](https://github.com/mahdibland/V2RayAggregator) |
| VPNBook | [Официальные Outline / Shadowsocks-конфигурации](https://www.vpnbook.com/freevpn/outline-vpn) |
| 0xRadikal · Top100 | [Free-v2ray-Configs](https://github.com/0xRadikal/Free-v2ray-Configs) |
| Matin · Super | [v2ray-configs](https://github.com/MatinGhanbari/v2ray-configs) |
| NodesRadar | [NodesRadar](https://github.com/nodesradar/nodesradar-en) |

В APK хранятся адреса источников. Конфигурации загружаются при обновлении;
дубли VPNBook, уже присутствующие в других загруженных списках, исключаются из его списка.
Подробности происхождения, лицензий и проверки — в [SOURCES.md](SOURCES.md).

Серверы принадлежат сторонним операторам. Их доступность, скорость и приватность
не гарантируются. У VPNBook есть журналы соединений. Приложение бесплатно;
стоимость собственной сторонней подписки определяет её провайдер.

## Для разработчиков

«Жужалка» — открытый форк [v2rayNG](https://github.com/2dust/v2rayNG) на Xray.
Новый интерфейс и операции разделены по существующим слоям проекта:

| Часть | Файл / каталог |
|---|---|
| Простой экран Compose | `ui/main/ZhuzhalkaScreen.kt` |
| Состояние и действия | `ui/main/MainViewModel.kt`, `ZhuzhalkaState.kt` |
| Каталог и импорт | `handler/PublicSourceCatalog.kt` |
| Официальный адаптер VPNBook | `handler/OfficialVpnBookSource.kt` |
| Веб-проверки через Xray | `core/ServiceWebProbe.kt` |
| Хранение | существующие `MmkvManager` и `SettingsManager` |

Пути в таблице относятся к `V2rayNG/app/src/main/java/com/v2ray/ang/`.
Детерминированная логика проверяется JUnit-тестами. Сетевые операции выполняются вне главного
потока, результаты сохранения читаются обратно, неудачное обновление сохраняет прежние профили.

```sh
git clone --recurse-submodules https://github.com/UserOfTheWorld-code/zhuzhalka.git
cd zhuzhalka
bash scripts/build-android.sh
```

Скрипт требует подготовленные JDK и Android SDK/NDK. Версии и подготовка окружения заданы
в [workflow](.github/workflows/build.yml). Он проверяет закреплённое native-ядро,
собирает HEV, запускает тесты и собирает обе debug-версии. Инструкции для Windows,
ограничения проверок и детали — в [README.zhuzhalka.md](README.zhuzhalka.md).

## Лицензия и благодарности

[GNU GPL v3](LICENSE). Сохраняйте лицензию и передавайте соответствующие исходники вместе с APK.
Спасибо авторам **v2rayNG**, **Xray**, **AndroidLibXrayLite** и **hev-socks5-tunnel**.
Это самостоятельная модифицированная сборка; она не является официальным релизом v2rayNG.
Исходное руководство сохранено в [README.upstream.md](README.upstream.md).
