# VirusPlugin

Плагин для Paper/Spigot 1.21.1 с тремя стадиями кастомного вируса, частицами и лечебным отваром.

## Сборка

Требуется Java 21 и Maven:

```bash
mvn clean package
```

Готовый файл появится в `target/VirusPlugin.jar`. Его нужно положить в папку `plugins/` сервера.

## Команды

```text
/virus infect <ник>
/virus cure <ник>
```

Право на команды: `virus.admin` (по умолчанию есть у операторов).

## Механика

- PvP-заражение от зараженного игрока: 30%.
- Заражение от зомби: 15%.
- Стадия 1: 10 минут.
- Стадия 2: следующие 15 минут, Hunger I и Slowness I, кашель, зеленые частицы.
- Стадия 3: до лечения, Weakness I, короткий Luck I при входе в пик и темные частицы.
- Рецепт отвара — shapeless-рецепт из Suspicious Stew, Glowstone Dust и Ghast Tear.

Состояние зараженных игроков хранится в `plugins/VirusPlugin/config.yml`.
