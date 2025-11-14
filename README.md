# cc-build-pruner (Java CLI)

Ein CLI, um alte, löschbare Builds in SAP Commerce Cloud zu listen und (optional) zu löschen.

## Voraussetzungen

* Java 17
* Maven 3.8+

## Build

```bash
mvn package
```

Der Build erzeugt in `target/` ein ausführbares JAR inklusive aller Abhängigkeiten.

## Nutzung

Die Anwendung nutzt [Picocli](https://picocli.info/) und stellt den Befehl `cc-build` mit den Unterbefehlen `list` und `prune` bereit.

Setze die benötigten Verbindungsinformationen entweder per Kommandozeilenoption oder über Umgebungsvariablen:

* `--base-url` (`CC_BASE_URL`)
* `--project-id` (`CC_PROJECT_ID`)
* `--environment-id` (`CC_ENVIRONMENT_ID`)
* `--token` (`CC_TOKEN`)

### Builds auflisten

```bash
java -jar target/cc-build-pruner-0.1.0.jar \
  list --limit 100
```

Weitere nützliche Optionen:

* `--limit` – Anzahl der abgefragten Builds (Standard: 50)
* `--include-non-deletable` – Zeigt auch nicht löschbare Builds
* `--json` – Gibt die Rohdaten als JSON aus

### Builds löschen

```bash
java -jar target/cc-build-pruner-0.1.0.jar \
  prune --older-than 30d --dry-run
```

* `--older-than` – Pflicht: ISO-8601 Dauer (z. B. `P14D`) oder Kurzform (`30d`, `12h`)
* `--dry-run` – Nur anzeigen, was gelöscht würde
* `--limit` – Anzahl der Builds, die zum Prüfen geladen werden (Standard: 200)
* `--max` – Maximale Anzahl zu löschender Builds (Standard: unbegrenzt)

Zum tatsächlichen Löschen `--dry-run` weglassen.
