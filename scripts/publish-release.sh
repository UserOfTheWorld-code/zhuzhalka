#!/usr/bin/env bash
# Publish the signed alpha draft after a successful, manually requested build.
set -euo pipefail

TAG=v0.3.0-alpha.3
: "${GH_TOKEN:?GitHub Actions must supply its job-scoped token.}"
: "${GITHUB_REPOSITORY:?Run this script in GitHub Actions.}"
: "${GITHUB_SHA:?Missing source commit.}"
[[ "${GITHUB_EVENT_NAME:-}" == workflow_dispatch && "${GITHUB_REF:-}" == refs/heads/master ]] || {
    printf '%s\n' 'Publishing requires a manual workflow run on master.' >&2; exit 1;
}
[[ "$(git rev-parse HEAD)" == "$GITHUB_SHA" ]] || {
    printf '%s\n' 'Checkout differs from the source commit.' >&2; exit 1;
}
ASSET_DIR="$(cd -- "${1:?Pass the release asset directory.}" && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
assets=(Zhuzhalka_0.3.0-alpha.3_arm64-v8a.apk Zhuzhalka_0.3.0-alpha.3_universal.apk Zhuzhalka_0.3.0-alpha.3_sources.zip SHA256SUMS)

python3 - "$ASSET_DIR" "$GITHUB_SHA" "$(dirname -- "${BASH_SOURCE[0]}")/release-signing-cert.sha256" <<'PY'
import hashlib, json, pathlib, sys, zipfile
directory = pathlib.Path(sys.argv[1])
expected = {"Zhuzhalka_0.3.0-alpha.3_arm64-v8a.apk", "Zhuzhalka_0.3.0-alpha.3_universal.apk", "Zhuzhalka_0.3.0-alpha.3_sources.zip"}
assert {p.name for p in directory.iterdir()} == expected | {"SHA256SUMS"}, "Unexpected release files"
lines = (directory / "SHA256SUMS").read_text(encoding="ascii").splitlines()
assert len(lines) == len(expected), "Unexpected checksum count"
actual = {}
for line in lines:
    checksum, name = line.split("  ", 1)
    assert name in expected and name not in actual, "Unexpected checksum entry"
    with (directory / name).open("rb") as stream:
        actual[name] = hashlib.file_digest(stream, "sha256").hexdigest()
    assert checksum == actual[name], f"Checksum mismatch: {name}"
with zipfile.ZipFile(directory / "Zhuzhalka_0.3.0-alpha.3_sources.zip") as archive:
    info = json.loads(archive.read("Zhuzhalka_0.3.0-alpha.3_sources/BUILD_INFO.json"))
    assert info["source_commit"] == sys.argv[2], "Source archive belongs to another commit"
    assert info["version"] == "0.3.0-alpha.3" and info["version_code"] == 5000302, "Unexpected release version"
    assert info["signing_certificate_sha256"] == pathlib.Path(sys.argv[3]).read_text(encoding="ascii").strip(), "Unexpected signing identity"
PY

# Never overwrite an already public release, including a successful earlier run.
[[ "$(gh release view "$TAG" --repo "$GITHUB_REPOSITORY" --json isDraft --jq '.isDraft')" == true ]] || {
    printf '%s\n' 'The alpha release must exist and still be a draft; public assets are never replaced.' >&2; exit 1;
}
# A pre-existing tag must resolve to exactly the commit whose APKs were built.
verify_tag() {
    local tag_object object_type object_sha
    tag_object="$(gh api "repos/$GITHUB_REPOSITORY/git/matching-refs/tags/$TAG" \
        --jq ".[] | select(.ref == \"refs/tags/$TAG\") | [.object.type, .object.sha] | @tsv")"
    if [[ -z "$tag_object" ]]; then
        [[ "$1" == optional ]] && return 0
        printf '%s\n' 'Published release has no matching tag.' >&2; return 1
    fi
    read -r object_type object_sha <<< "$tag_object"
    for _ in 1 2 3 4 5; do
        [[ "$object_type" != tag ]] && break
        tag_object="$(gh api "repos/$GITHUB_REPOSITORY/git/tags/$object_sha" \
            --jq '[.object.type, .object.sha] | @tsv')"
        read -r object_type object_sha <<< "$tag_object"
    done
    [[ "$object_type" == commit && "$object_sha" == "$GITHUB_SHA" ]] || {
        printf '%s\n' 'The alpha tag points to another commit; it will not be moved.' >&2; exit 1;
    }
}
verify_tag optional
existing_assets="$(gh release view "$TAG" --repo "$GITHUB_REPOSITORY" --json assets --jq '.assets[].name')"
while IFS= read -r existing; do
    case "$existing" in
        ''|Zhuzhalka_0.3.0-alpha.3_arm64-v8a.apk|Zhuzhalka_0.3.0-alpha.3_universal.apk|Zhuzhalka_0.3.0-alpha.3_sources.zip|SHA256SUMS) ;;
        *) printf 'Unexpected draft asset: %s\n' "$existing" >&2; exit 1 ;;
    esac
done <<< "$existing_assets"

run_url="${GITHUB_SERVER_URL:-https://github.com}/$GITHUB_REPOSITORY/actions/runs/${GITHUB_RUN_ID:?}"
cat > "$work_dir/notes.md" <<EOF
## Жужалка 0.3.0-alpha.3 — alpha

Бесплатный Android-клиент на базе v2rayNG: открытые каталоги серверов, автоматический подбор и импорт сторонних подписок.

Изменения 0.3.0-alpha.3:

- экран «Поделиться Жужалкой»: QR-код и ссылка на страницу релизов GitHub; для скачивания по ссылке нужен интернет;
- передача установленного APK через системное меню Android, в том числе Bluetooth или Quick Share (Wi-Fi), если они доступны на устройстве;
- APK содержит приложение без личных подписок и настроек; если установка состоит из нескольких частей, предлагается передать ссылку;
- передаётся установленная версия для её архитектуры: при несовместимости получателю нужен universal APK со страницы релизов. Наличие транспорта и доставку файла определяет Android.

Сохранён экспериментальный обмен сообщества из alpha.1:

- «Сеть Жужалки»: добровольный обмен оценками и предложениями списков через независимые публичные relay Nostr, без собственного платного backend; по умолчанию участие выключено;
- после включения публично передаются хеши серверов встроенных каталогов, результаты и время веб-проверок, округлённая задержка и публичный ключ установки; relay также видит IP соединения;
- актуальные оценки не старше часа от как минимум двух ключей при большинстве успешных проверок могут поднять в очереди максимум три кандидата; недавние локальные успехи и выбранный сервер сохраняют приоритет;
- подписи защищают целостность данных, но несколько ключей могут принадлежать одному человеку; каждый кандидат по-прежнему проходит локальные проверки совместимости, TLS и трёх HTTPS-адресов;
- личная подписка публикуется только отдельным действием и подтверждением: полный URL, включая токен доступа, становится доступен всем участникам и операторам relay; делиться можно только своей подпиской с разрешения провайдера;
- полученный список загружается только после просмотра полного URL и отдельного подтверждения; отзыв предложения не удаляет чужие копии.

Обмен выполняется короткими операциями в открытом приложении. Постоянная связь между всеми телефонами, фоновая доставка и доступность relay не гарантируются. Это alpha-версия публичного протокола: закрытые группы друзей и зашифрованная передача подписок не реализованы. Протокол и результаты прошлой alpha.1 описаны в docs/community-network.md; проверки оформления alpha.2 сохранены в docs/version-0.3-alpha.2-validation.md. Передача приложения описана в docs/version-0.3-alpha.3-validation.md.

Сохраняются автоподбор с повторной проверкой недавних успешных и сохранённых профилей, загрузка источников только при необходимости, три параллельные HTTPS-проверки одного профиля и подтверждение отключения работающего VPN перед подбором. Проверка TLS не отключается; после выбора подключитесь снова.

Бюджет поиска — 180 секунд, включая загрузки списков. Лимиты этапов ограничивают начало новых операций: уже начатые native-вызовы могут закончиться позже и не прерываются мгновенно при отмене. Фиксированное время поиска не обещается. Веб-проверки не подтверждают работу сообщений, входа и звонков в приложениях.

Сохраняются обновления прямо в приложении с проверкой SHA-256, пакета, версии и подписи, системным подтверждением установки Android и постоянной подписью для последующих выпусков.

- **arm64-v8a.apk** — для большинства современных Android-телефонов.
- **universal.apk** — вариант со всеми поддерживаемыми архитектурами.
- **sources.zip** — исходники этого коммита, подмодули и готовые нативные библиотеки.
- **SHA256SUMS** — контрольные суммы APK и архива исходников.

APK собраны в [GitHub Actions]($run_url) из коммита $GITHUB_SHA. Перед упаковкой прошли модульные тесты, компиляция и сборка обеих версий приложения; подписи APK и контрольные суммы проверены.

Начиная с 0.2 APK подписаны постоянным приватным ключом Жужалки. Следующие версии с тем же ключом и большим кодом версии можно устанавливать поверх 0.2, сохраняя профили. Android запрашивает подтверждение установки.

**Переход с 0.1:** у первой beta была временная debug-подпись, поэтому её нельзя обновить поверх старой установки. Сначала экспортируйте нужные профили/подписки, удалите 0.1, установите новый выпуск с постоянной подписью и импортируйте данные. Это однократный переход на постоянную подпись.

Эта публикация подтверждает сборку, модульные тесты и проверку сертификатов; APK из данного облачного запуска отдельно в эмуляторе не проверялись.

Бесплатные серверы принадлежат третьим лицам: доступность, скорость и сохранность данных не гарантируются. Клиент не требует оплаты, но сторонняя подписка может быть платной. Это независимый форк v2rayNG, лицензия GPL-3.0.
EOF

# Retrying an interrupted run may replace assets only while the release is draft.
gh release edit "$TAG" --repo "$GITHUB_REPOSITORY" --draft --prerelease \
    --target "$GITHUB_SHA" --title 'Жужалка 0.3.0-alpha.3 — alpha' --notes-file "$work_dir/notes.md"
upload_files=()
for asset in "${assets[@]}"; do upload_files+=("$ASSET_DIR/$asset"); done
gh release upload "$TAG" --repo "$GITHUB_REPOSITORY" --clobber "${upload_files[@]}"

# Read every uploaded byte back before exposing the release to downloaders.
mkdir "$work_dir/downloaded"
gh release download "$TAG" --repo "$GITHUB_REPOSITORY" --dir "$work_dir/downloaded" \
    --pattern 'Zhuzhalka_0.3.0-alpha.3_*.apk' --pattern 'Zhuzhalka_0.3.0-alpha.3_sources.zip' --pattern SHA256SUMS
for asset in "${assets[@]}"; do cmp -- "$ASSET_DIR/$asset" "$work_dir/downloaded/$asset"; done
gh release view "$TAG" --repo "$GITHUB_REPOSITORY" --json assets > "$work_dir/assets.json"
python3 - "$work_dir/assets.json" <<'PY'
import json, sys
assets = json.load(open(sys.argv[1], encoding="utf-8"))["assets"]
expected = {"Zhuzhalka_0.3.0-alpha.3_arm64-v8a.apk", "Zhuzhalka_0.3.0-alpha.3_universal.apk", "Zhuzhalka_0.3.0-alpha.3_sources.zip", "SHA256SUMS"}
assert len(assets) == 4 and {asset["name"] for asset in assets} == expected, "Unexpected draft assets"
PY
gh release edit "$TAG" --repo "$GITHUB_REPOSITORY" --draft=false --prerelease --latest=false
verify_tag required
gh release view "$TAG" --repo "$GITHUB_REPOSITORY" --json url,isDraft,isPrerelease,assets > "$work_dir/published.json"
python3 - "$work_dir/published.json" <<'PY'
import json, sys
release = json.load(open(sys.argv[1], encoding="utf-8"))
assert release["isDraft"] is False and release["isPrerelease"] is True, "Unexpected release state"
expected = {"Zhuzhalka_0.3.0-alpha.3_arm64-v8a.apk", "Zhuzhalka_0.3.0-alpha.3_universal.apk", "Zhuzhalka_0.3.0-alpha.3_sources.zip", "SHA256SUMS"}
assert {asset["name"] for asset in release["assets"]} == expected, "Unexpected published assets"
print(release["url"])
PY
