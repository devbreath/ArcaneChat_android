# Workflow форка: свои фичи + PR в апстрим

Этот документ описывает, как вести `devbreath/ArcaneChat_android` так, чтобы
одни фичи можно было отправлять в апстрим `ArcaneChat/android`, а другие
развивать только в форке.

## Идея коротко

- `main` — чистое зеркало апстрима. Своего кода здесь нет.
- `fork-main` — сборка форка: сюда попадает всё (и кандидаты в апстрим,
  и чисто форковые фичи). Релизы форка собираются из неё.
- `upstream/<фича>` — чистая ветка под один PR в апстрим.
- `fork/<фича>` — фича, которая живёт только в форке.

```text
upstream/main (ArcaneChat/android)
    |
    v
origin/main (зеркало, без своего кода)
    |
    v
origin/fork-main (интеграция форка: всё вместе)
   / \
  /   \
upstream/*  fork/*   (фича-ветки)
```

Правило: **никогда не смешивай `fork-only` код в `upstream/*` ветки.**
Апстрим-ветка должна собираться и работать сама по себе.

## Именование веток

| Префикс | Назначение | База | Куда идёт PR |
|---|---|---|---|
| `upstream/<name>` | кандидат в апстрим | `upstream/main` | `ArcaneChat/android:main` |
| `fork/<name>` | только для форка | `fork-main` | `devbreath: fork-main` |
| `fork-main` | интеграция форка | `upstream/main` + мерджи фич | — (ветка сборки/релиза) |
| `main` | зеркало апстрима | `upstream/main` | — (только sync, без PR) |

Примеры:

- `upstream/text-selection`
- `upstream/link-preview-fix`
- `fork/youtube-sharing`
- `fork/custom-theme`

Старые ветки (до введения workflow) маппятся так:

| Старая ветка | Что это | Что с ней делать |
|---|---|---|
| `upstream-pr-text-selection` | ✅ чистая, PR ArcaneChat/android#181 | держать до мержа, только rebase + fixup |
| `feat_part_selection` | ❌ черновая (19 коммитов с `Agent Host changes`) | не трогать / архивировать, в апстрим идёт `upstream-pr-*` |
| `feat_youtube_stream`, `agents/youtube-*` | форковые YouTube-фичи | продолжать как `fork/youtube-*` |

## Метки PR/Issue в форке

- `upstream` — хотим отдать в апстрим.
- `fork-only` — остаётся только в форке, в апстрим не предлагать.

Создаются один раз:

```powershell
gh label create upstream --repo devbreath/ArcaneChat_android `
  --color 0E8A16 --description "Candidate for upstream PR"
gh label create fork-only --repo devbreath/ArcaneChat_android `
  --color D93F0B --description "Stays in fork, not for upstream"
```

## 1. Синхронизация с апстримом

Делать регулярно (раз в несколько дней или перед стартом новой фичи).

```powershell
git fetch upstream --prune
git checkout main
git merge --ff-only upstream/main
git push origin main
git checkout fork-main
git merge main
git push origin fork-main
```

Если `main` не fast-forwardится (кто-то закоммитил в него напрямую) —
разбирай вручную, в `main` своего кода быть не должно.

После обновления `fork-main` подтяни свои рабочие ветки:

```powershell
# для upstream-веток — rebase, чтобы история была линейной:
git checkout upstream/my-feature
git rebase upstream/main

# для fork-веток — merge из fork-main (или rebase, если ветка локальная):
git checkout fork/my-feature
git merge fork-main
```

## 2. Новая фича для апстрима

```powershell
git fetch upstream
git checkout -b upstream/my-feature upstream/main
# ... код ...
git push -u origin upstream/my-feature
gh pr create --repo devbreath/ArcaneChat_android `
  --head upstream/my-feature --base fork-main `
  --label upstream --draft `
  --title "feat: ..." --body "..."
```

Довёл до готовности — открываешь PR в апстрим **из той же ветки**:

1. `gh pr create --repo ArcaneChat/android --head devbreath:upstream/my-feature --base main`
   (или кнопкой Compare на GitHub).
2. Внутрифорковый PR (чтобы фича попала в сборку форка):
   `devbreath upstream/my-feature -> devbreath fork-main`, тоже с меткой `upstream`.

Требования к `upstream/*` ветке:

- один PR = одна фича;
- squash лишних `fix`, `Agent Host changes` перед отправкой
  (`git rebase -i upstream/main`);
- описание на английском, как принято в апстриме;
- никакого `fork-only` кода, отдельных build-флагов форка, личных настроек.

Когда апстрим принял PR:

```powershell
git fetch upstream
git checkout main
git merge --ff-only upstream/main
git checkout fork-main
git merge main
git branch -d upstream/my-feature
git push origin --delete upstream/my-feature
```

Ветка в форке удаляется — её содержимое уже приехало через `main`.

## 3. Новая фича только для форка

```powershell
git checkout fork-main
git pull
git checkout -b fork/my-feature
# ... код ...
git push -u origin fork/my-feature
gh pr create --repo devbreath/ArcaneChat_android `
  --head fork/my-feature --base fork-main `
  --label fork-only `
  --title "feat: ..." --body "fork-only: ..."
```

- База — всегда `fork-main`, не `main` (иначе потеряешь другие форковые фичи при тесте).
- В PR явно пиши `fork-only`, чтобы случайно не отправить в апстрим.
- PR в `ArcaneChat/android` из `fork/*` веток **не создавать**.

## 4. Проверка перед PR в апстрим

Чеклист, чтобы не уронить чужой CI и не притащить лишнее:

```powershell
git fetch upstream
git log --oneline upstream/main..HEAD          # только твои коммиты
git diff --stat upstream/main..HEAD            # только нужные файлы
git diff upstream/main..HEAD -- FEATURE_*.md PLAN_*.md docs/  # нет ли черновиков
```

Если в diff есть `FEATURE_TEXT_SELECTION.md`, `PLAN_*.md`, личные скрипты —
убери их из `upstream/*` (оставь в `fork/*` или не коммить).

## 5. Релизы

- Релиз форка: тег на `fork-main` (`v*.*.*`), собирает `.github/workflows/release.yml`.
- Зеркальный `main` тегов не ставит — теги апстрима подтягиваются через fetch.
- Preview-APK собирается на каждый PR внутри форка (см. `preview-apk.yml`).

## 6. Конфликты

- Конфликт при `fork-main <- main`: резолвишь в пользу апстрима,
  затем возвращаешь форковую логику поверх (свои куски не терять молча —
  проверь `git diff` после мержа).
- Конфликт в `upstream/*` при rebase: правишь, `git rebase --continue`,
  затем `git push --force-with-lease`.
- Никогда не делай `git push --force` в `main`, `fork-main`, `upstream/main`.

## Шпаргалка

```powershell
# sync
git fetch upstream --prune; git checkout main; git merge --ff-only upstream/main
git push origin main; git checkout fork-main; git merge main; git push origin fork-main

# фича в апстрим
git checkout -b upstream/<name> upstream/main

# фича в форк
git checkout -b fork/<name> fork-main

# апстрим-фичу в сборку форка (внутрифорковый PR)
gh pr create --repo devbreath/ArcaneChat_android `
  --head upstream/<name> --base fork-main --label upstream

# что уедет в PR
git log --oneline upstream/main..HEAD
```
