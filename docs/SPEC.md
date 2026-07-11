# AppLimit — Projektspezifikation

*Arbeitssprache des Projekts war Deutsch; dieses Dokument ist die bereinigte Fassung
der verbindlichen Spezifikation, gegen die alle acht Meilensteine implementiert und
abgenommen wurden. Entstanden am 2026-07-10, Revisionen vom selben Tag am Ende.*

## 1. Zielbild und Scope (v1)

Native Android-App für den Eigengebrauch. Distribution per Sideload (signierte APK via
`adb install`), kein Play Store — dadurch keine Play-Policy-Restriktionen für den
AccessibilityService. Kein Backend, keine Accounts, alle Daten lokal.

Featureset v1:

- Limits pro App: Öffnungen/Tag, Minuten/Tag, oder beides
- App-Gruppen mit gemeinsamem Budget (z. B. „Social Media: 30 min/Tag" über mehrere Apps;
  eine App gehört höchstens einer Gruppe an)
- Wochentagspläne: frei kombinierbare Tagesmengen (z. B. Mo–Fr und Sa–So); jeder Tag
  gehört genau einem Plan an
- Zeitfenster-Sperren pro Plan (z. B. gesperrt vor 9:00 und nach 21:00)
- Vorwarnungen: Notification bei 80 % des Zeitbudgets bzw. nach Verbrauch der vorletzten
  Öffnung; dezentes Countdown-Overlay in der letzten Minute
- Friction-Block mit unbegrenzten Durchgängen (siehe Verhaltensmodell)
- Statistik-Dashboard: Öffnungen, Nutzungszeit, Limit-Treffer und Friction-Durchgänge
  pro App/Gruppe und Tag; Wochentrends

Explizit nicht in v1: Manipulationsschutz gegen sich selbst (Deinstallation oder
Deaktivieren des AccessibilityService bleibt möglich; echter Schutz bräuchte
Device-Owner-Provisioning), Backup/Sync, Mehrgeräte-Support, Play-Store-Tauglichkeit.

## 2. Verhaltensmodell (präzise Regeln)

- **Tagesreset:** 00:00 lokale Zeit, `LocalDate`-basiert in der Gerätezeitzone
  (DST-sicher; Umstellungsnächte dürfen Budgets nicht verdoppeln oder verschlucken).
- **Öffnung:** Wechsel einer überwachten App in den Vordergrund zählt als neue Öffnung,
  wenn die App zuvor >60 Sekunden nicht im Vordergrund war (Debounce gegen kurze
  App-Switches). Bildschirm aus >60 s beendet die Session ebenfalls.
- **Zeitzählung:** nur während die App im Vordergrund ist, das Gerät interaktiv und der
  Keyguard entsperrt ist; Gruppenbudgets akkumulieren über alle Mitglieds-Apps.
- **Warnstufen:** 80 % des Minutenbudgets → Notification; vorletzte Öffnung verbraucht →
  Notification; letzte 60 Sekunden → kleines Countdown-Overlay am Bildschirmrand.
- **Limit erreicht → Friction-Block, unbegrenzt wiederholbar:** Vollbild-Block-Screen
  mit 5-Sekunden-Countdown. Danach zwei Freigabe-Optionen je nach Limit-Typ:
  SMALL (+1 Minute bzw. +1 Öffnung) oder LARGE (+5 Minuten bzw. +3 Öffnungen), bei
  kombinierten Limits kombiniert. Kein Tagesdeckel für Durchgänge — jeder Durchgang
  wird fürs Dashboard protokolliert. Abbruch verbraucht keine Öffnung.
- **Zeitfenster-Sperre:** außerhalb des erlaubten Fensters harter Block ohne
  Friction-Option; der Screen zeigt die nächste Freigabezeit und löst sich dann von
  selbst auf. Fenster sind halboffen `[Start, Ende)`.
- **Konfigurationsänderungen gelten sofort** — auch Lockerungen (siehe Revisionen).
- AppLimit selbst, Launcher, Tastaturen und System-UI werden niemals geblockt.

## 3. Zielplattform

- Samsung Galaxy S25 Ultra, Android 16 (API 36), One UI 8.5; minSdk/targetSdk 36
- Sideload-Hinweis: Androids „Eingeschränkte Einstellungen" blockieren die Aktivierung
  des AccessibilityService nur bei Browser-/Datei-Manager-Sideloads — bei
  adb-Installation erscheint die Sperre nicht
- One-UI-Spezifika: Ausnahme von der Batterieoptimierung plus Eintrag in „Nie
  schlafende Apps" nötig; Foreground Service ab API 34 mit Typ-Deklaration
  (`specialUse`)

## 4. Architektur und Stack

- Kotlin 2.x, Jetpack Compose + Material 3, Room, DataStore, Single-Module-Projekt
- Detection: `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`) als primäre Quelle;
  Screen-on/off- und Unlock-Signale; Abgleich gegen die reale Fensterliste
- Enforcement: Overlay (`SYSTEM_ALERT_WINDOW`) plus `GLOBAL_ACTION_HOME`
- Engine: reine Kotlin-Klasse `UsageEngine` mit injizierbarer Clock — vollständig
  unit-testbar, keine Android-APIs
- Persistenz-Robustheit: Zähler alle ~7 s und bei jedem Session-Ende flushen
- Zuverlässigkeit: Foreground Service als Anker, `BOOT_COMPLETED`-Receiver, Watchdog

## 5. Datenmodell (Room)

`App`, `Group`, `Plan` (Wochentags-Set), `LimitRule` (Ziel App/Gruppe; Typ
Öffnungen/Minuten/Zeitfenster), `UsageSession`, `DailyCounter` (inkl. gewährter
Extensions), `FrictionEvent`. Historisierung von Anfang an — Sessions und Tageszähler
werden nie gelöscht.

## 6. Meilensteine (je mit Abnahmekriterium)

1. **Setup + Berechtigungs-Onboarding** — geführter Flow für alle Berechtigungen.
   *Abnahme: Status-Screen komplett grün.*
2. **Detection-Engine** — Vordergrund-Tracking, Sessions, Debug-Ansicht.
   *Abnahme: Öffnungen und Sekunden stimmen gegen Stoppuhr-Prüfung.*
3. **Datenmodell + Konfigurations-UI** — App-Auswahl, Gruppen, Pläne, Limits.
   *Abnahme: Referenzkonfiguration (10 Öffnungen/Tag bzw. 10 Minuten/Tag, getrennte
   Pläne Mo–Fr und Sa–So) vollständig anlegbar.*
4. **Enforcement** — Warnstufen, Friction, Zeitfenster-Hardblock.
   *Abnahme: kompletter Tageszyklus manuell durchgespielt.*
5. **Tagesreset** — Reset-Logik inkl. DST-/Zeitzonenwechsel.
   *Abnahme: Engine-Tests mit simulierter Clock grün.*
6. **Dashboard** — Tages-/Wochenansicht, Friction-Historie.
7. **Robustheit** — Foreground-Anker, Boot-Recovery, Watchdog, One-UI-Härtung.
8. **Geräte-Verifikation** — manuelle Endabnahme-Checkliste plus automatisierte
   Regression (`verify-device.ps1`).

## 7. Risiken (vorab identifiziert)

Die Logik ist einfach; das Risiko ist Prozess-Persistenz (OEM-Battery-Management) und
die Zuverlässigkeit der Accessibility-Events — beides hat sich in der Umsetzung
bestätigt (siehe Fallstudien im `docs/`-Verzeichnis).

## 8. Entscheidungslog

Ursprüngliche Entscheidungen (2026-07-10):

- Blockverhalten: Friction-Block statt hartem Block, unbegrenzte Durchgänge
- v1-Umfang: alle vier Zusatzfeatures (Vorwarnungen, Dashboard, Gruppen, Zeitfenster)
- Defaults: Reset um Mitternacht, 60-s-Öffnungs-Debounce, Zeitfenster ohne Friction

Revisionen während der Implementierung (nach Praxistest am Gerät):

- Friction-Countdown: 5 statt 30 Sekunden
- Friction-Freigabe: zwei typabhängige Optionen (SMALL/LARGE) statt einer festen
- 60-s-Öffnungs-Debounce nach Praxistest ausdrücklich bestätigt
- Commitment-Logik („Lockerungen erst ab morgen") implementiert, abgenommen — und nach
  dem Praxistest bewusst wieder verworfen: alle Konfigurationsänderungen gelten sofort
