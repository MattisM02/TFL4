# Was macht dieses Programm? -- Einfach erklärt

Eine verständliche Erklärung des Benchmark-Frameworks, auch ohne Programmierkenntnisse.

---

## Inhaltsverzeichnis

1. [Das Ziel in einem Satz](#1-das-ziel-in-einem-satz)
2. [Grundlagen: Was ist Java und die JVM?](#2-grundlagen-was-ist-java-und-die-jvm)
3. [Grundlagen: Was ist Garbage Collection?](#3-grundlagen-was-ist-garbage-collection)
4. [Grundlagen: Was ist JIT-Kompilierung?](#4-grundlagen-was-ist-jit-kompilierung)
5. [Grundlagen: Was ist Docker?](#5-grundlagen-was-ist-docker)
6. [Grundlagen: Was ist die Cloud?](#6-grundlagen-was-ist-die-cloud)
7. [Was macht das Programm konkret?](#7-was-macht-das-programm-konkret)
8. [Die 12 Einstellungen, die verglichen werden](#8-die-12-einstellungen-die-verglichen-werden)
9. [Was wird gemessen?](#9-was-wird-gemessen)
10. [Wie liest man die Ergebnisse?](#10-wie-liest-man-die-ergebnisse)
11. [Was kann man mit den Ergebnissen anfangen?](#11-was-kann-man-mit-den-ergebnissen-anfangen)
12. [Ausblick: Was könnte man noch testen?](#12-ausblick-was-könnte-man-noch-testen)

---

## 1. Das Ziel in einem Satz

Dieses Programm testet **12 verschiedene Einstellungen** für Java-Anwendungen und misst, welche Einstellung am schnellsten startet, die niedrigsten Antwortzeiten hat, den höchsten Durchsatz liefert und am wenigsten Ressourcen verbraucht -- und zwar unter den Bedingungen, die in einer echten Cloud herrschen.

**Analogie:** Man stelle sich ein Auto vor, das immer die gleiche Teststrecke fährt. Aber jedes Mal werden die Motoreinstellungen verändert -- Sportmodus, Eco-Modus, mit Turbo, ohne Turbo. Nach jeder Fahrt wird gemessen: Wie schnell war der Motor auf Betriebstemperatur? Wie schnell war die Rundenzeit? Wie viel Sprit wurde verbraucht? Am Ende hat man eine Tabelle, die zeigt, welche Motoreinstellung für welchen Einsatzzweck am besten ist.

Genau das machen wir hier -- nur mit Java-Programmen statt mit Autos.

---

## 2. Grundlagen: Was ist Java und die JVM?

**Java** ist eine Programmiersprache. Programme, die in Java geschrieben sind, laufen nicht direkt auf dem Computer, sondern in einer Art "Übersetzungsmaschine" -- der **Java Virtual Machine (JVM)**.

### Warum gibt es die JVM?

Normalerweise müsste ein Programm für jedes Betriebssystem (Windows, Linux, macOS) separat erstellt werden. Die JVM löst das Problem: Man schreibt das Programm einmal in Java, und die JVM übersetzt es zur Laufzeit in die Sprache des jeweiligen Betriebssystems. Das Programm läuft also überall, wo eine JVM installiert ist.

### Warum ist das für uns relevant?

Die JVM hat viele **Einstellungen** (sogenannte "Flags"), die beeinflussen, wie sie arbeitet. Diese Einstellungen wirken sich auf Geschwindigkeit, Speicherverbrauch und Startzeit aus. Unser Programm testet, welche Einstellungen unter welchen Umständen am besten funktionieren.

### Was ist ein "Flag"?

Ein Flag ist ein Einstellungsparameter, den man der JVM beim Start mitgibt. Zum Beispiel:
- `-Xmx512m` sagt: "Du darfst maximal 512 Megabyte Arbeitsspeicher verwenden"
- `-XX:+UseZGC` sagt: "Verwende die Aufräumstrategie namens ZGC"

Man kann sich Flags wie Schieberegler und Schalter an einem Mischpult vorstellen: Jede Kombination ergibt ein anderes Klangbild -- oder in unserem Fall ein anderes Leistungsprofil.

---

## 3. Grundlagen: Was ist Garbage Collection?

### Das Problem: Speicher wird knapp

Wenn ein Java-Programm läuft, erzeugt es ständig Daten im Arbeitsspeicher -- zum Beispiel Kundendaten, Berechnungsergebnisse, Zwischenwerte. Irgendwann werden diese Daten nicht mehr gebraucht. Aber der Speicher ist begrenzt.

### Die Lösung: Ein automatischer Aufräumer

In Java gibt es einen automatischen Mechanismus, der nicht mehr benötigte Daten aus dem Speicher entfernt. Dieser Mechanismus heißt **Garbage Collector** (GC) -- wörtlich: "Müllsammler".

### Das Dilemma

Wenn der Garbage Collector aufräumt, muss er manchmal das gesamte Programm kurz **anhalten** (eine sogenannte "Pause" oder "Stop-the-World"). Während dieser Pause reagiert das Programm nicht auf Anfragen. Das ist so, als würde ein Restaurant kurz schließen, damit das Reinigungspersonal durchfegen kann.

Es gibt verschiedene GC-Strategien mit unterschiedlichen Ansätzen:

| Strategie | Analogie | Vorteil | Nachteil |
|-----------|----------|---------|----------|
| **Serial GC** | Ein einzelner Reinigungskraft, alle müssen warten | Verbraucht wenig Ressourcen | Längste Pausen |
| **Parallel GC** | Mehrere Reinigungskräfte gleichzeitig, alle müssen warten | Schnell fertig, hoher Durchsatz | Immer noch Pausen |
| **G1 GC** | Reinigungskraft räumt nur einzelne Zimmer auf, Restaurant bleibt offen | Guter Kompromiss | Etwas mehr Overhead |
| **ZGC** | Reinigungskraft räumt auf, während Gäste bedient werden | Fast keine Pausen spürbar | Braucht mehr CPU |
| **Shenandoah** | Ähnlich wie ZGC, aber andere Technik | Fast keine Pausen | Etwas mehr Speicherbedarf |

Unser Programm testet alle fünf Strategien und misst, welche unter den gegebenen Bedingungen am besten funktioniert.

---

## 4. Grundlagen: Was ist JIT-Kompilierung?

### Das Problem: Java startet langsam

Wenn ein Java-Programm startet, wird der Code zuerst nur **interpretiert** -- das heißt, die JVM liest jede Anweisung einzeln und führt sie aus. Das ist wie ein Simultandolmetscher, der jeden Satz einzeln übersetzt: funktioniert, aber langsam.

### Die Lösung: Just-In-Time Kompilierung

Nach einer Weile erkennt die JVM, welche Codestellen häufig ausgeführt werden ("hot code"). Diese Stellen werden dann in **Maschinencode** übersetzt -- also in die Sprache, die der Prozessor direkt versteht. Das ist viel schneller, als jedes Mal neu zu dolmetschen.

Dieser Vorgang heißt **JIT-Kompilierung** (Just-In-Time = "gerade rechtzeitig").

### Zwei Stufen

Die JVM hat zwei Übersetzer (Compiler):
- **C1** (schneller Übersetzer): Übersetzt Code schnell, aber die Übersetzung ist nicht perfekt optimiert. Ergebnis: Das Programm wird schnell etwas schneller.
- **C2** (gründlicher Übersetzer): Braucht länger, erzeugt aber besseren Code. Ergebnis: Das Programm erreicht nach einer Aufwärmphase seine volle Geschwindigkeit.

### Warum ist das relevant?

Im Cloud-Betrieb werden Container (dazu gleich mehr) häufig gestartet und gestoppt. Wenn ein Container nur kurz lebt, kommt der C2-Compiler gar nicht dazu, seine Arbeit zu beenden. In diesem Fall wäre es besser, C2 ganz abzuschalten und nur C1 zu verwenden -- das Programm startet dann deutlich schneller.

Genau das testet eine unserer 12 Konfigurationen: `tiered-stop-1` schaltet den langsamen, aber gründlichen C2-Compiler ab.

---

## 5. Grundlagen: Was ist Docker?

### Das Problem: "Bei mir funktioniert's"

Ein häufiges Problem in der Softwareentwicklung: Ein Programm läuft auf dem Computer des Entwicklers, aber nicht auf dem Server. Der Grund: Unterschiedliche Betriebssystem-Versionen, fehlende Bibliotheken, andere Einstellungen.

### Die Lösung: Container

**Docker** verpackt ein Programm zusammen mit allem, was es braucht (Betriebssystem-Bibliotheken, Java-Version, Konfiguration) in einen **Container**. Dieser Container funktioniert überall gleich -- egal ob auf einem Laptop, einem Server oder in der Cloud.

Man kann sich einen Container vorstellen wie einen Umzugskarton: Alles ist sauber verpackt, beschriftet, und kann an jedem Ort ausgepackt und sofort benutzt werden.

### Ressourcenlimits

Ein wichtiger Aspekt von Docker: Man kann einem Container feste **Ressourcenlimits** geben:
- "Du bekommst maximal 1 CPU" (= eine Recheneinheit)
- "Du bekommst maximal 768 MB Arbeitsspeicher"

Das bildet die Realität in der Cloud nach, wo jeder Container nur einen begrenzten Anteil der Gesamtressourcen bekommt.

### Warum ist das relevant?

Unser Benchmark startet für jede der 12 Konfigurationen einen **eigenen** Docker-Container mit exakt den gleichen Limits. So werden die Bedingungen fair und vergleichbar: Jede Konfiguration bekommt genau 1 CPU und 768 MB RAM -- nicht mehr, nicht weniger.

---

## 6. Grundlagen: Was ist die Cloud?

### Traditionell: Ein Server pro Anwendung

Früher lief jede Anwendung auf einem eigenen physischen Server. Brauchte man mehr Kapazität, kaufte man einen größeren Server. Problem: Die meiste Zeit war der Server nicht ausgelastet, kostete aber trotzdem Strom und Platz.

### Cloud: Viele Anwendungen teilen sich Hardware

In der Cloud teilen sich viele Anwendungen die gleiche Hardware. Jede Anwendung läuft in einem Container mit definierten Ressourcenlimits. Ein **Orchestrator** (meistens Kubernetes) entscheidet, auf welchem Server welcher Container läuft, und kann Container bei Bedarf starten, stoppen oder auf andere Server verschieben.

### Warum sind JVM-Einstellungen in der Cloud besonders wichtig?

1. **Kosten:** In der Cloud bezahlt man pro CPU und pro GB RAM. Wenn ein Container durch bessere JVM-Einstellungen mit weniger Speicher auskommt, spart das Geld.
2. **Startup-Zeit:** Wenn plötzlich viele Anfragen kommen, müssen schnell neue Container gestartet werden (Autoscaling). Ein Container, der 10 Sekunden zum Starten braucht statt 3, reagiert zu langsam.
3. **Ressourcenlimits:** Container haben feste Grenzen. Der Garbage Collector muss innerhalb dieser Grenzen effizient arbeiten, sonst wird der Container vom System beendet (OOM-Kill = "Out of Memory", der Arbeitsspeicher ist voll).
4. **Dichte:** Je weniger Ressourcen ein Container braucht, desto mehr Container passen auf einen Server. Das erhöht die Auslastung und senkt die Kosten.

---

## 7. Was macht das Programm konkret?

### Zwei Teile

Das Programm besteht aus zwei Teilen:

**Teil 1: Die Anwendung (System Under Test)**
Eine Spring-Boot-Webanwendung mit drei verschiedenen Aufgaben:
- **JSON:** 200.000 Datenobjekte erzeugen und als JSON zurückgeben (stresst den Prozessor)
- **Alloc:** 10 Millionen kurzlebige Speicherblöcke erzeugen (stresst den Garbage Collector)
- **EBICS Upload:** Eine echte Banküberweisung über das EBICS-Protokoll durchführen (realistischer Workload)

**Teil 2: Das Benchmark-Werkzeug**
Ein Kommandozeilen-Programm, das:
1. Für jede der 12 Konfigurationen einen Docker-Container startet
2. Die Anwendung darin hochfahren lässt
3. Erst 200 "Aufwärm-Anfragen" schickt (damit die JVM sich aufwärmt)
4. Dann 500 "echte" Anfragen schickt und jede einzelne Antwortzeit misst
5. Währenddessen den CPU- und Speicherverbrauch aufzeichnet
6. Den Container stoppt und die Ergebnisse speichert
7. Das Ganze 3× wiederholt, jedes Mal in zufälliger Reihenfolge

### Warum zufällige Reihenfolge?

Wenn man immer in der gleichen Reihenfolge testet (erst A, dann B, dann C), könnte die letzte Konfiguration benachteiligt sein -- der Computer ist nach vielen Tests "wärmer", der Prozessor drosselt vielleicht die Geschwindigkeit. Durch zufällige Reihenfolge werden solche Verzerrungen vermieden.

### Warum 3 Wiederholungen?

Eine einzelne Messung kann zufällig gut oder schlecht ausfallen. Durch 3 Wiederholungen kann man den **Durchschnitt** berechnen und sehen, wie stark die Ergebnisse **schwanken** (Standardabweichung). Je weniger sie schwanken, desto verlässlicher sind sie.

### Schnelldurchlauf (--quick)

Mit der Option `--quick` läuft eine verkürzte Variante: nur 10 Aufwärm-Anfragen, 30 Mess-Anfragen und 1 Wiederholung. Das dauert nur wenige Minuten statt mehrerer Stunden und eignet sich zum Testen, ob alles funktioniert. Für die endgültigen Ergebnisse in der Thesis werden die vollen 200/500/3 verwendet.

### Ablauf eines einzelnen Runs (Schritt für Schritt)

```
1. Docker-Container starten
   → Mit genau 1 CPU, 768 MB RAM, kein Swap
   → JVM-Flags werden als Umgebungsvariable übergeben

2. Warten, bis die Anwendung bereit ist
   → Regelmäßig nachfragen: "Bist du schon da?"
   → Die Wartezeit wird gemessen (= "Startup-Zeit")

3. Ruhe-Messung (IDLE)
   → 3× CPU und Speicher messen, BEVOR Anfragen kommen
   → Zeigt den Grundverbrauch

4. Erste Anfrage
   → Die allererste Anfrage ist oft besonders langsam
   → Wird separat gemessen ("First Request")

5. Aufwärmen (Warmup)
   → 200 Anfragen schicken, Ergebnisse verwerfen
   → Der JIT-Compiler optimiert währenddessen den Code

6. Mess-Anfragen
   → 500 Anfragen schicken, JEDE Antwortzeit aufzeichnen
   → Gleichzeitig: 10× CPU und Speicher messen (LOAD)

7. Ruhe-Messung nach Last (POST)
   → 3× CPU und Speicher nach dem Test messen
   → Zeigt, wie schnell sich die Anwendung erholt

8. Container stoppen und entfernen

9. Ergebnisse speichern (CSV, JSON, Excel)
```

---

## 8. Die 12 Einstellungen, die verglichen werden

### Gruppe 1: Welcher Aufräumer ist der beste? (5 Varianten)

Dies sind fünf verschiedene Garbage-Collection-Strategien. Jede hat einen anderen Ansatz, wie nicht mehr benötigter Speicher freigegeben wird.

#### 1. baseline -- Der Standard
Keine besonderen Einstellungen. Java verwendet den Standard-Aufräumer **G1 GC**. Das ist der Referenzpunkt: Alle anderen Konfigurationen werden mit dieser verglichen.

G1 teilt den Speicher in viele kleine Regionen auf und räumt immer die Regionen auf, die am meisten Müll enthalten. Das Programm wird dabei kurz angehalten, aber die Pausen sind meist kürzer als bei älteren Methoden. Man kann sich das vorstellen wie einen Putzdienst, der immer zuerst das dreckigste Zimmer reinigt.

#### 2. zgc -- Der Schnelle ohne Unterbrechung
**Flag:** `-XX:+UseZGC`

ZGC (Z Garbage Collector) ist ein moderner Aufräumer, der das Programm so gut wie **nie** anhalten muss. Er räumt auf, während das Programm normal weiterarbeitet. Die Pausen liegen im Bereich von unter einer Millisekunde (eine Tausendstelsekunde) -- egal wie viel Speicher verwaltet wird.

Der Preis: ZGC braucht etwas mehr Rechenleistung, weil er nebenbei arbeiten muss.

**Wann sinnvoll?** Wenn extrem schnelle und gleichmäßige Antwortzeiten wichtig sind -- zum Beispiel bei Online-Banking oder Echtzeit-Systemen.

#### 3. shenandoah -- Der Alternative ohne Unterbrechung
**Flag:** `-XX:+UseShenandoahGC`

Shenandoah verfolgt das gleiche Ziel wie ZGC (minimale Pausen), aber mit einer anderen Technik. Während ZGC mit "farbigen Zeigern" arbeitet, verwendet Shenandoah "Weiterleitungszeiger". Beide erreichen ähnlich niedrige Pausen.

**Warum beide testen?** Um zu sehen, ob einer der beiden unter unseren spezifischen Bedingungen (1 CPU, 768 MB) besser abschneidet.

#### 4. parallel-gc -- Der Schnellarbeiter
**Flag:** `-XX:+UseParallelGC`

Der Parallel Collector hält das Programm an, setzt dann aber **mehrere Aufräumer gleichzeitig** ein, um möglichst schnell fertig zu werden. Er ist optimiert auf **maximalen Durchsatz**: Die Gesamtzeit, die für Aufräumarbeiten draufgeht, soll minimal sein.

Der Nachteil: Wenn er aufräumt, steht das Programm komplett still -- und diese Pausen können länger sein als bei G1.

**Wann sinnvoll?** Bei Hintergrund-Jobs, die möglichst viele Aufgaben pro Stunde erledigen sollen und wo es nicht auf einzelne Antwortzeiten ankommt.

#### 5. serial-gc -- Der Minimalist
**Flag:** `-XX:+UseSerialGC`

Der Serial Collector verwendet nur **einen einzigen** Aufräumer-Thread. Kein Koordinationsaufwand, kein Kommunikation zwischen Threads, minimaler Speicherverbrauch für die Aufräum-Infrastruktur.

**Warum ist das interessant?** Unser Container hat nur 1 CPU. Aufräumer mit mehreren Threads (wie G1 oder Parallel) müssen ihre Threads auf dieser einen CPU abwechselnd ausführen -- das kostet Koordinationsaufwand, der gar keinen Nutzen bringt, wenn sowieso nur ein Kern verfügbar ist. Serial GC könnte in dieser Situation effizienter sein, weil er diesen Overhead nicht hat.

### Gruppe 2: Den Standard-Aufräumer feintunen (3 Varianten)

Diese drei Konfigurationen verwenden alle den Standard-Aufräumer G1, aber mit unterschiedlichen Einstellungen.

#### 6. g1-low-pause -- Kürzere Pausen erzwingen
**Flag:** `-XX:+UseG1GC -XX:MaxGCPauseMillis=50`

Normalerweise versucht G1, Pausen unter 200 Millisekunden zu halten. Hier setzen wir das Ziel auf 50 Millisekunden. G1 räumt dann häufiger auf, aber jede einzelne Pause ist kürzer.

**Analogie:** Statt einmal am Tag 20 Minuten zu putzen, putzt man viermal am Tag je 5 Minuten. Die Gesamtputzzeit kann etwas steigen, aber die Unterbrechungen sind kürzer.

#### 7. g1-heap-256m -- Wenig Speicher erlauben
**Flag:** `-Xmx256m`

Normalerweise berechnet Java selbst, wie viel Speicher es verwenden soll. Hier sagen wir: "Du bekommst maximal 256 MB", obwohl der Container 768 MB hat.

**Warum?** Wenn die Anwendung mit 256 MB auskommt, könnte man in der Cloud kleinere (und billigere) Container verwenden. Der Aufräumer muss allerdings häufiger arbeiten, weil der Speicher schneller voll wird.

#### 8. g1-heap-512m -- Mittlerer Speicher
**Flag:** `-Xmx512m`

Ein Mittelweg: Mehr als 256 MB, aber nicht der volle Container-Speicher. Zeigt, wie sich ein moderates Speicherlimit auswirkt.

### Gruppe 3: Cloud-spezifische Einstellungen (2 Varianten)

#### 9. ram-percentage-75 -- Speicher prozentual zuweisen
**Flag:** `-XX:MaxRAMPercentage=75`

Seit Java 10 erkennt die JVM automatisch, in welchem Container sie läuft und wie viel Speicher der Container hat. Mit diesem Flag sagt man: "Verwende maximal 75% des Container-Speichers für den Heap."

Bei unserem 768-MB-Container sind das ~576 MB. Der Rest (192 MB) bleibt für andere JVM-Komponenten (Metadaten, Thread-Speicher, JIT-Code-Cache).

**Warum 75%?** In der Praxis hat sich herausgestellt, dass 75% ein guter Wert ist. Bei 90% reicht der restliche Speicher oft nicht für die anderen JVM-Komponenten, und der Container wird vom System beendet.

#### 10. tiered-stop-1 -- Schnellstart-Modus
**Flag:** `-XX:TieredStopAtLevel=1`

Dieses Flag schaltet den langsamen, aber gründlichen C2-Compiler komplett ab (siehe Abschnitt 4: JIT-Kompilierung). Das Programm startet **deutlich schneller**, weil die aufwändige C2-Optimierung entfällt.

**Der Preis:** Das Programm erreicht nie seine volle Höchstgeschwindigkeit, weil der C2-Compiler nie die aggressiven Optimierungen durchführt.

**Wann sinnvoll?** Wenn Container häufig gestartet und gestoppt werden -- zum Beispiel bei Autoscaling (automatisches Hochfahren neuer Instanzen bei Last) oder bei serverlosen Funktionen (ein Container wird nur für einen einzigen Aufruf gestartet). In diesen Fällen ist der Container beendet, bevor der C2-Compiler überhaupt seine Arbeit beenden könnte.

### Gruppe 4: Interne Speicher-Optimierungen (2 Varianten)

#### 11. coops-off -- Größere Zeiger verwenden
**Flag:** `-XX:-UseCompressedOops`

**Hintergrund:** Java läuft auf 64-Bit-Systemen, aber die meisten Programme brauchen keine 64-Bit-Adressierung. Normalerweise komprimiert Java daher seine internen Zeiger (Referenzen auf Objekte im Speicher) von 8 Byte auf 4 Byte. Das spart erheblich Speicher.

Mit diesem Flag schalten wir diese Komprimierung **aus**. Jede Referenz belegt dann 8 Byte statt 4. Das Programm braucht mehr Speicher, und die Daten passen schlechter in den Prozessor-Cache.

**Warum testen?** Um zu **messen**, wie groß der Effekt der Zeiger-Komprimierung tatsächlich ist. Das liefert eine Zahl, die in der Thesis zitiert werden kann.

#### 12. coh-on -- Kompaktere Objekt-Köpfe
**Flag:** `-XX:+UseCompactObjectHeaders`

**Hintergrund:** Jedes Java-Objekt hat einen internen "Kopf" (Object Header), der Verwaltungsinformationen enthält (Typ des Objekts, Hash-Code, GC-Status). Dieser Kopf ist normalerweise 12-16 Byte groß.

Dieses experimentelle Feature komprimiert den Kopf auf 8 Byte. Bei Workloads, die Millionen kleiner Objekte erzeugen (wie unsere JSON-Serialisierung mit 200.000 Objekten), spart das signifikant Speicher.

---

## 9. Was wird gemessen?

Für **jede** der 12 Konfigurationen werden exakt die **gleichen** Messwerte erhoben. Es wird nichts weggelassen oder unterschiedlich behandelt. Das macht die Ergebnisse direkt vergleichbar.

### Startup-Zeit (Readiness)
**Was:** Wie lange dauert es vom Starten des Containers bis das Programm bereit ist, Anfragen zu beantworten?
**Einheit:** Millisekunden
**Warum wichtig:** In der Cloud müssen neue Instanzen schnell verfügbar sein. Eine Startup-Zeit von 10 Sekunden statt 3 bedeutet, dass Nutzer 7 Sekunden länger warten müssen, wenn gerade hochskaliert wird.

### Erster Request (First Request)
**Was:** Wie lange dauert die allererste Anfrage nach dem Start?
**Einheit:** Sekunden
**Warum wichtig:** Die erste Anfrage ist oft besonders langsam, weil der JIT-Compiler noch nicht optimiert hat und viele Klassen zum ersten Mal geladen werden. Diese Zeit zeigt den "Cold-Start-Effekt".

### Antwortzeiten (Latenzen) -- p50, p95, p99, Mean
**Was:** Wie lange dauern die 500 Mess-Anfragen?
**Einheit:** Sekunden

Statt nur den Durchschnitt zu berechnen, werden **Perzentile** verwendet:
- **p50 (Median):** 50% der Anfragen waren schneller als dieser Wert. Zeigt das "typische" Verhalten.
- **p95:** 95% der Anfragen waren schneller. Zeigt, was "die meisten" Nutzer erleben.
- **p99:** 99% der Anfragen waren schneller. Zeigt das **Worst-Case-Verhalten** -- die langsamsten 1% der Anfragen. In der Praxis werden SLAs (Service-Level-Agreements) oft auf p95 oder p99 definiert.
- **Mean:** Der arithmetische Durchschnitt. Kann durch einzelne Ausreißer verfälscht werden, deshalb sind Perzentile aussagekräftiger.

**Beispiel:** p50 = 0.02s, p99 = 0.15s bedeutet: Die Hälfte aller Anfragen ist in 20 Millisekunden beantwortet, aber die langsamsten 1% brauchen 150 Millisekunden. Ein Garbage Collector, der seltene, aber lange Pausen verursacht, zeigt sich im p99-Wert.

### Durchsatz (Throughput)
**Was:** Wie viele Anfragen pro Sekunde kann die Anwendung verarbeiten?
**Einheit:** Requests pro Sekunde (req/s)
**Berechnung:** 500 Mess-Requests ÷ Gesamtdauer der Messphase
**Warum wichtig:** Direktes Maß für die Kapazität. Ein Container mit 100 req/s schafft doppelt so viele Nutzer wie einer mit 50 req/s. In der Cloud bedeutet höherer Durchsatz: weniger Container nötig = niedrigere Kosten.

### CPU-Auslastung
**Was:** Wie viel Prozent der zugewiesenen CPU nutzt der Container?
**Einheit:** Prozent
**Phasen:**
- **IDLE:** CPU-Verbrauch im Ruhezustand (vor dem Test). Zeigt den Grundverbrauch durch JVM-Hintergrundprozesse.
- **LOAD:** CPU-Verbrauch während der 500 Mess-Anfragen. Zeigt den Verbrauch unter Last.
- **POST:** CPU-Verbrauch direkt nach dem Test. Zeigt, ob der Garbage Collector noch aufräumt.

### Speicherauslastung
**Was:** Wie viel Prozent des zugewiesenen Speichers (768 MB) nutzt der Container?
**Einheit:** Prozent
**Phasen:** Gleich wie bei CPU (IDLE, LOAD, POST).
**Warum wichtig:** Ein Container, der 90% Speicher nutzt, ist am Limit. Bei kurzzeitigen Lastspitzen kann er den Container-Kill-Schwellwert überschreiten. Ein Container mit 50% hat genug Puffer.

---

## 10. Wie liest man die Ergebnisse?

Die Ergebnisse werden in einer Excel-Datei mit 5 Tabs (Sheets) dargestellt:

### Sheet 1: Übersicht
Eine große Tabelle mit **allen** Messwerten für **alle** Konfigurationen. Jede Zeile ist ein einzelner Testlauf. Die Spalten sind in Gruppen eingeteilt:
- **Konfiguration:** Name, Docker-Image, JVM-Flags
- **Startup:** Readiness-Zeit, First Request
- **Latenzen:** p50, p95, p99, Mean
- **Durchsatz:** Requests pro Sekunde
- **Docker LOAD:** CPU%, Mem% während Last
- **Messprofil:** Wie viele Warmup/Mess-Requests
- **Meta:** Wiederholungsnummer, verwendeter Readiness-Check

Die Tabelle hat Filter, mit denen man nach bestimmten Konfigurationen oder Szenarien filtern kann.

### Sheet 2: Latenzen
Ein **Balkendiagramm**, das für jede Konfiguration die drei Perzentile (p50, p95, p99) nebeneinander zeigt:
- **Grüne Balken:** p50 (Median, typische Latenz)
- **Orange Balken:** p95 (die meisten Anfragen)
- **Rote Balken:** p99 (Worst-Case)

**Wie lesen:** Je kürzer die Balken, desto besser. Wenn die roten Balken deutlich länger sind als die grünen, verursacht der GC gelegentliche lange Pausen.

### Sheet 3: Startup & Throughput
Zwei Diagramme:
1. **Startup-Zeit:** Dunkelblauer Balken pro Konfiguration. Kürzer = schneller startbereit.
2. **Durchsatz:** Grüner Balken pro Konfiguration. Länger = mehr Anfragen pro Sekunde = besser.

### Sheet 4: Ressourcen
Ein Diagramm mit CPU- und Speicherverbrauch unter Last:
- **Orange Balken:** CPU% während LOAD
- **Dunkelblaue Balken:** Mem% während LOAD

**Wie lesen:** Niedrigere Werte bedeuten effizientere Ressourcennutzung. Hohe Speicherwerte (nahe 100%) sind kritisch.

### Sheet 5: Rohdaten
Alle 500 Einzellatenzen jeder Konfiguration. Gedacht für eigene statistische Auswertungen (z.B. Histogramme, Verteilungsanalysen) in einem separaten Tool.

---

## 11. Was kann man mit den Ergebnissen anfangen?

### Empfehlungen ableiten

Nach dem Benchmark hat man Daten, um fundierte Empfehlungen zu geben:

**Beispiel-Ergebnisse (hypothetisch):**

| Konfiguration | Startup | p95-Latenz | Durchsatz | Speicher |
|---------------|---------|-----------|-----------|---------|
| baseline | 5.2s | 0.08s | 120 req/s | 65% |
| zgc | 5.8s | 0.03s | 105 req/s | 70% |
| serial-gc | 4.8s | 0.15s | 130 req/s | 55% |
| tiered-stop-1 | 2.1s | 0.10s | 85 req/s | 60% |

**Mögliche Schlussfolgerungen:**
- Für **latenz-kritische Services** (z.B. Online-Banking): ZGC verwenden -- niedrigste p95-Latenz
- Für **kurzlebige Container** (Autoscaling): `tiered-stop-1` -- halb so lange Startup-Zeit
- Für **Batch-Jobs** mit wenig Speicher: Serial GC -- geringster Speicherverbrauch
- Für **Allrounder**: Baseline (G1) -- bester Kompromiss

### In der Thesis

Die Ergebnisse liefern **messbare Belege** für Empfehlungen zur Container-Konfiguration. Statt "ZGC sollte niedrigere Latenzen haben" kann man schreiben: "ZGC reduziert die p95-Latenz im JSON-Szenario um 62% gegenüber der Baseline (0.03s vs. 0.08s) bei einem Throughput-Rückgang von 12.5%."

---

## 12. Ausblick: Was könnte man noch testen?

### Flag-Kombinationen

Aktuell testen wir jede Einstellung einzeln. In der Praxis kombiniert man oft mehrere Flags. Relevante Kombinationen:

| Kombination | Idee |
|-------------|------|
| ZGC + 75% RAM | Niedriger Latenz-Collector mit container-bewusstem Heap |
| ZGC + Schnellstart | Niedrige Latenzen UND schneller Startup |
| Serial GC + 256 MB Heap | Absolut minimaler Ressourcenverbrauch |
| G1 + Kompakte Object-Header | Speicherersparnis beim Standard-Collector |

### GraalVM Native Image

GraalVM ist eine alternative Java-Plattform, die den Code **vor** dem Start in ein natives Programm kompiliert (Ahead-of-Time statt Just-in-Time). Damit entfällt die gesamte JVM-Startphase:
- **Startup:** Millisekunden statt Sekunden
- **Speicher:** Deutlich weniger
- **Durchsatz:** Etwas geringer als nach vollständiger JIT-Optimierung

Das Framework könnte mit einem zusätzlichen Dockerfile für GraalVM erweitert werden, um den Vergleich JVM vs. Native Image zu ermöglichen.

### GC-Log-Auswertung

Aktuell werden GC-Logs für alle Konfigurationen aufgezeichnet (in der Container-Ausgabe), aber nicht automatisch ausgewertet. Eine mögliche Erweiterung: GC-Logs parsen und als zusätzliche Messwerte in die Excel aufnehmen (Anzahl GC-Pausen, durchschnittliche Pausendauer, GC-Overhead in Prozent).
