# TV Remote auf dem iPhone installieren

Anleitung für ein iPhone (zum Beispiel das iPhone 13 mini) mit einem MacBook. Es kostet nichts.
Stand: 9. November 2026, nach der AltStore-Dokumentation (Links ganz unten).

## Worum es geht

Die App liegt nicht im App Store. Du bekommst sie als Datei (`TVRemote-0.4.5-unsigned.ipa`) und installierst sie mit
**AltStore**. Das Programm trägt deine Apple-ID in die App ein, damit das iPhone sie öffnen darf.

Die Sache hat einen Haken, und der kommt von Apple, nicht von der App: Mit einer kostenlosen Apple-ID **läuft eine
selbst installierte App nach 7 Tagen ab** und lässt sich dann nicht mehr öffnen. Sie muss einmal pro Woche
„aufgefrischt" werden. Das erledigt AltStore von selbst, wenn das MacBook an und im selben WLAN ist (Schritt 6).
Der Rest der Zeit läuft die App ganz ohne MacBook. Deine Einstellungen und die Kopplung mit dem Fernseher bleiben
beim Auffrischen erhalten.

## Das brauchst du

- iPhone mit **iOS 16 oder neuer** (Einstellungen → Allgemein → Info → iOS-Version)
- MacBook mit **macOS 11 oder neuer**
- das Kabel zwischen iPhone und MacBook (nur für die Einrichtung)
- eine Apple-ID. Am sichersten ist eine **zusätzliche, nur dafür angelegte Apple-ID** (kostenlos auf appleid.apple.com).
  Die Zugangsdaten gehen nur an Apple, trotzdem muss deine Haupt-ID nicht dabei sein.
- WLAN, in dem iPhone und MacBook beide sind (kein Gäste- oder Firmen-WLAN)

## Schritt 1: AltServer auf dem MacBook installieren (einmalig)

1. Auf dem MacBook **altstore.io** öffnen und „AltServer für macOS" laden.
2. Die Datei entpacken und `AltServer.app` in den Ordner **Programme** ziehen.
3. AltServer starten. Danach erscheint ein kleines Symbol oben in der Menüleiste.
4. Wenn macOS nach Benachrichtigungen fragt: erlauben (sonst siehst du nicht, ob die Installation geklappt hat).

## Schritt 2: AltStore auf das iPhone bringen (einmalig)

1. iPhone per Kabel mit dem MacBook verbinden, **entsperren** und auf dem iPhone „Diesem Computer vertrauen" antippen.
2. Auf dem MacBook den **Finder** öffnen, links das iPhone anklicken und die Option
   **„Dieses iPhone anzeigen, wenn es mit dem WLAN verbunden ist"** einschalten (Haken setzen, „Anwenden"; bei AltStore
   heißt die Option „Show this iPhone when on WiFi", der deutsche Wortlaut kann leicht abweichen). Das braucht AltStore,
   um sich später über das WLAN aufzufrischen.
3. In der Menüleiste auf das **AltServer-Symbol** klicken → **„Install AltStore"** → dein iPhone auswählen.
4. Die Apple-ID und das Passwort eingeben (und den Code, falls Apple einen schickt). Beides geht nur an Apple.
   Kommt ein Hinweis „Mail-Plug-in installieren", die Schritte aus dem Hinweis befolgen.
5. Warten, bis eine Meldung „Installation Succeeded" erscheint. Wenn AltStore danach nicht auf dem Home-Bildschirm
   auftaucht: iPhone einmal neu starten.

## Schritt 3: AltStore auf dem iPhone erlauben (einmalig)

1. **Einstellungen → Allgemein → VPN & Geräteverwaltung** → auf deine Apple-ID tippen → **„Vertrauen"** → bestätigen.
2. **Einstellungen → Datenschutz & Sicherheit → Entwicklermodus** → einschalten. Das iPhone startet neu; nach dem Neustart
   erscheint eine Frage, die du mit **„Einschalten"** und deinem Code bestätigst.
   (Der Schalter taucht meist erst auf, nachdem AltStore installiert ist. Fehlt er, Schritt 2 wiederholen.)

## Schritt 4: die App laden und installieren

1. Auf dem **iPhone** in Safari diese Seite öffnen:
   `https://github.com/mikachu1302/android-tv-remote/releases/tag/v0.4.5`
2. Unter „Assets" auf **`TVRemote-0.4.5-unsigned.ipa`** tippen. Safari fragt, ob geladen werden soll: „Laden".
   Die Datei liegt danach in der App **Dateien** im Ordner **Downloads**.
3. **AltStore** öffnen → unten **„My Apps"** → oben links auf das **„+"** → die Datei `TVRemote-0.4.5-unsigned.ipa`
   auswählen → bei Nachfrage mit der Apple-ID anmelden.
4. Warten. Wenn **TV Remote** in der Liste „My Apps" steht und auf dem Home-Bildschirm erscheint, ist sie installiert.

## Schritt 5: erster Start und Kopplung

1. **TV Remote** öffnen. Das iPhone fragt, ob die App **Geräte im lokalen Netzwerk finden** darf: **„Erlauben"**.
   (Ohne das findet sie den Fernseher nicht. Falls du „Nicht erlauben" getippt hast: Einstellungen → TV Remote →
   „Lokales Netzwerk" einschalten.)
2. Der Fernseher oder Stick muss an und im **selben WLAN** sein. Die App zeigt ihn nach ein paar Sekunden an
   („Find your TV"). Auf den Namen tippen.
3. Auf dem Fernseher erscheint ein **Code mit 6 Zeichen**. In der App eingeben und **„Pair"** antippen.
4. Fertig. Du siehst die Fernbedienung. Nach rechts wischen zeigt die Zifferntasten und die weiteren Tasten.
   Das Mikrofon (Sprachsuche) fragt beim ersten Gedrückthalten nach der Erlaubnis.
5. Die Lautstärke des Hisense-Fernsehers selbst: **Einstellungen (Zahnrad) → TV volume → Choose**. Beim ersten Mal fragt
   der Fernseher auf dem Bildschirm, ob das iPhone ihn steuern darf: mit der alten Fernbedienung erlauben.

Die App ist auf Englisch.

## Schritt 6: jede Woche auffrischen

Die App läuft 7 Tage ab der Installation oder dem letzten Auffrischen. Damit sie nicht abläuft:

- **MacBook einschalten**, AltServer läuft (Symbol in der Menüleiste), iPhone und MacBook im **selben WLAN**.
- In **AltStore** am iPhone **„My Apps" → „Refresh All"** antippen. Ist in AltStore (Reiter „Settings") **„Background
  Refresh"** eingeschaltet, wacht AltStore von Zeit zu Zeit von selbst auf und frischt die Apps auf, sobald das MacBook
  erreichbar ist. Dafür muss auch die iOS-Hintergrundaktualisierung für AltStore an sein (Einstellungen → Allgemein →
  Hintergrundaktualisierung).
- Ist die App trotzdem abgelaufen: Sie lässt sich nicht mehr öffnen. Dann einfach auffrischen wie oben. Normalerweise
  sind Einstellungen und Kopplung danach noch da; falls nicht, einmal neu koppeln (Schritt 5).

Eine neue Version der App installierst du genauso wie in Schritt 4: die neue Datei laden und über „+" installieren. Sie
ersetzt die alte; Einstellungen und Kopplung bleiben normalerweise erhalten.

## Grenzen der kostenlosen Apple-ID

- Höchstens **3 selbst installierte Apps** gleichzeitig. Wenn es voll ist, lässt sich in AltStore eine App deaktivieren
  (ihre Daten werden gesichert, der Platz wird frei).
- Es sind höchstens **10 „App-IDs"** gleichzeitig möglich; jede läuft nach einer Woche aus. Wird das knapp, einfach ein paar
  Tage warten.

## Wenn etwas nicht klappt

- **AltStore findet AltServer nicht / „Could not find AltServer":** MacBook muss an sein, AltServer laufen, beide Geräte im
  selben WLAN (kein Gäste- oder Firmen-WLAN). Hilft alles nichts: iPhone per Kabel anschließen und es nochmal versuchen.
- **Die Anmeldung mit der Apple-ID hängt oder schlägt fehl:** nochmal versuchen, Zugang auf appleid.apple.com prüfen oder
  eine neue, nur dafür angelegte Apple-ID verwenden.
- **App lässt sich nicht öffnen („Nicht vertrauenswürdiger Entwickler" oder „Entwicklermodus"):** Schritt 3 prüfen.
- **„Can't reach the TV, retrying":** Fernseher oder Stick an? Gleiches WLAN? „Lokales Netzwerk" für TV Remote erlaubt?
  Tippen auf die Statuszeile versucht es sofort neu.
- **Pairing schlägt fehl:** dem Fernseher einen Moment geben und nochmal. Der Fernseher zeigt dann einen neuen Code.

**Wichtig:** Die iPhone-App ist neu. Sie wurde in einer Testumgebung mit einem nachgebauten Fernseher geprüft, aber noch nicht
an einem echten iPhone mit dem echten Stick. Wenn irgendwo eine Fehlermeldung erscheint, bitte ein Foto oder den Text
der Meldung schicken, dann lässt sich das schnell beheben.

## Quellen

- AltStore, Installation unter macOS: https://faq.altstore.io/altstore-classic/how-to-install-altstore-macos
- AltStore, Grundlagen, Auffrischen und Grenzen: https://faq.altstore.io/altstore-classic/your-altstore
- AltStore, App-IDs: https://faq.altstore.io/altstore-classic/app-ids
- AltStore, Fehlersuche: https://faq.altstore.io/altstore-classic/troubleshooting-guide
- AltServer, Versionshinweise: https://faq.altstore.io/release-notes/altserver
