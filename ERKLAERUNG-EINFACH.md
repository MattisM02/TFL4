# Was macht dieses Programm? -- Einfach erklaert

Eine verstaendliche Erklaerung des Benchmark-Frameworks, auch ohne Programmierkenntnisse.

---

## Inhaltsverzeichnis

1. [Das Ziel in einem Satz](#1-das-ziel-in-einem-satz)
2. [Grundlagen: Was ist Java und die JVM?](#2-grundlagen-was-ist-java-und-die-jvm)
3. [Grundlagen: Was ist Garbage Collection?](#3-grundlagen-was-ist-garbage-collection)
4. [Grundlagen: Was ist JIT-Kompilierung?](#4-grundlagen-was-ist-jit-kompilierung)
5. [Grundlagen: Was ist Docker?](#5-grundlagen-was-ist-docker)
6. [Grundlagen: Was ist die Cloud?](#6-grundlagen-was-ist-die-cloud)
7. [Was macht das Programm konkret?](#7-was-macht-das-programm-konkret)
8. [Die Zwei-Ebenen-Analyse: Warum 34 Konfigurationen?](#8-die-zwei-ebenen-analyse-warum-34-konfigurationen)
9. [Ebene 1: Die 21 Flag-Konfigurationen](#9-ebene-1-die-21-flag-konfigurationen)
10. [Ebene 2: Die 13 Laufzeitprofile](#10-ebene-2-die-13-laufzeitprofile)
11. [Was wird gemessen?](#11-was-wird-gemessen)
12. [GC-Log-Auswertung: Dem Aufraeumer ueber die Schulter schauen](#12-gc-log-auswertung-dem-aufraeumer-ueber-die-schulter-schauen)
13. [Statistik: Wie verlaesslich sind die Ergebnisse?](#13-statistik-wie-verlaesslich-sind-die-ergebnisse)
14. [Wie liest man die Ergebnisse?](#14-wie-liest-man-die-ergebnisse)
15. [Was kann man mit den Ergebnissen anfangen?](#15-was-kann-man-mit-den-ergebnissen-anfangen)
16. [GraalVM Native Image: Welche Probleme mussten geloest werden?](#16-graalvm-native-image-welche-probleme-mussten-geloest-werden)

---

## 1. Das Ziel in einem Satz

Dieses Programm testet **34 verschiedene Konfigurationen** fuer Java-Anwendungen und misst, welche am schnellsten startet, die niedrigsten Antwortzeiten hat, den hoechsten Durchsatz liefert und am wenigsten Ressourcen verbraucht -- und zwar unter den Bedingungen, die in einer echten Cloud herrschen.

**Analogie:** Man stelle sich ein Auto vor, das immer die gleiche Teststrecke faehrt. Aber jedes Mal werden die Motoreinstellungen veraendert -- Sportmodus, Eco-Modus, mit Turbo, ohne Turbo. Manchmal wird sogar ein komplett anderer Motor eingebaut (Diesel, Benziner, Elektro). Nach jeder Fahrt wird gemessen: Wie schnell war der Motor auf Betriebstemperatur? Wie schnell war die Rundenzeit? Wie viel Sprit wurde verbraucht? Am Ende hat man eine Tabelle, die zeigt, welche Motoreinstellung fuer welchen Einsatzzweck am besten ist.

Genau das machen wir hier -- nur mit Java-Programmen statt mit Autos.

---

## 2. Grundlagen: Was ist Java und die JVM?

**Java** ist eine Programmiersprache. Programme, die in Java geschrieben sind, laufen nicht direkt auf dem Computer, sondern in einer Art "Uebersetzungsmaschine" -- der **Java Virtual Machine (JVM)**.

### Warum gibt es die JVM?

Normalerweise muesste ein Programm fuer jedes Betriebssystem (Windows, Linux, macOS) separat erstellt werden. Die JVM loest das Problem: Man schreibt das Programm einmal in Java, und die JVM uebersetzt es zur Laufzeit in die Sprache des jeweiligen Betriebssystems. Das Programm laeuft also ueberall, wo eine JVM installiert ist.

### Warum ist das fuer uns relevant?

Die JVM hat viele **Einstellungen** (sogenannte "Flags"), die beeinflussen, wie sie arbeitet. Diese Einstellungen wirken sich auf Geschwindigkeit, Speicherverbrauch und Startzeit aus. Unser Programm testet, welche Einstellungen unter welchen Umstaenden am besten funktionieren.

### Was ist ein "Flag"?

Ein Flag ist ein Einstellungsparameter, den man der JVM beim Start mitgibt. Zum Beispiel:
- `-Xmx512m` sagt: "Du darfst maximal 512 Megabyte Arbeitsspeicher verwenden"
- `-XX:+UseZGC` sagt: "Verwende die Aufraeumstrategie namens ZGC"

Man kann sich Flags wie Schieberegler und Schalter an einem Mischpult vorstellen: Jede Kombination ergibt ein anderes Klangbild -- oder in unserem Fall ein anderes Leistungsprofil.

### Was ist ein "JVM-Hersteller"?

Es gibt nicht nur eine JVM. Verschiedene Firmen und Organisationen haben ihre eigenen JVM-Versionen gebaut, die alle Java-Programme ausfuehren koennen, aber intern anders arbeiten:

| JVM | Hersteller | Besonderheit |
|-----|-----------|-------------|
| **HotSpot** (Eclipse Temurin) | Eclipse Foundation | Die Standard-JVM, die fast ueberall eingesetzt wird |
| **OpenJ9** (IBM Semeru) | IBM/Eclipse | Spart oft weniger Speicher, hat eigene Aufraeumstrategien |
| **GraalVM** | Oracle | Kann Java-Programme auch direkt in Maschinenprogramme umwandeln |

**Analogie:** Alle drei sind "Java-Motoren" -- sie bringen das gleiche Auto zum Fahren. Aber sie sind von verschiedenen Ingenieuren gebaut und haben daher unterschiedliche Staerken: Einer verbraucht weniger Sprit, ein anderer beschleunigt schneller.

Unser Programm testet alle drei, um herauszufinden, welcher Motor unter welchen Bedingungen am besten ist.

---

## 3. Grundlagen: Was ist Garbage Collection?

### Das Problem: Speicher wird knapp

Wenn ein Java-Programm laeuft, erzeugt es staendig Daten im Arbeitsspeicher -- zum Beispiel Kundendaten, Berechnungsergebnisse, Zwischenwerte. Irgendwann werden diese Daten nicht mehr gebraucht. Aber der Speicher ist begrenzt.

### Die Loesung: Ein automatischer Aufraeumer

In Java gibt es einen automatischen Mechanismus, der nicht mehr benoetigte Daten aus dem Speicher entfernt. Dieser Mechanismus heisst **Garbage Collector** (GC) -- woertlich: "Muellsammler".

### Das Dilemma

Wenn der Garbage Collector aufraeumt, muss er manchmal das gesamte Programm kurz **anhalten** (eine sogenannte "Pause" oder "Stop-the-World"). Waehrend dieser Pause reagiert das Programm nicht auf Anfragen. Das ist so, als wuerde ein Restaurant kurz schliessen, damit das Reinigungspersonal durchfegen kann.

Es gibt verschiedene GC-Strategien mit unterschiedlichen Ansaetzen:

### HotSpot-Aufraeumer (Standard-JVM)

| Strategie | Analogie | Vorteil | Nachteil |
|-----------|----------|---------|----------|
| **Serial GC** | Ein einzelner Reinigungskraft, alle muessen warten | Verbraucht wenig Ressourcen | Laengste Pausen |
| **Parallel GC** | Mehrere Reinigungskraefte gleichzeitig, alle muessen warten | Schnell fertig, hoher Durchsatz | Immer noch Pausen |
| **G1 GC** | Reinigungskraft raeumt nur einzelne Zimmer auf, Restaurant bleibt offen | Guter Kompromiss | Etwas mehr Overhead |
| **ZGC** | Reinigungskraft raeumt auf, waehrend Gaeste bedient werden | Fast keine Pausen spuerbar | Braucht mehr CPU |
| **Shenandoah** | Aehnlich wie ZGC, aber andere Technik | Fast keine Pausen | Etwas mehr Speicherbedarf |

### OpenJ9-Aufraeumer (IBM-JVM)

OpenJ9 hat seine eigenen Aufraeumstrategien, die anders heissen und anders funktionieren:

| Strategie | Analogie | Beschreibung |
|-----------|----------|-------------|
| **gencon** (Standard) | Zwei Teams: Eines raeumt die neuen Sachen schnell weg, das andere kuemmert sich um den Rest | Gut fuer Programme mit vielen kurzlebigen Daten |
| **balanced** | Aufteilen in Zonen, jede Zone wird einzeln aufgeraeumt | Aehnlich wie G1: raeumt dort auf, wo am meisten Muell liegt |
| **optthruput** | Alles auf einmal aufraeuemen, dafuer moeglichst selten | Maximaler Durchsatz, aber laengere Pausen |
| **optavgpause** | Nebenbei aufraeuemen, um Pausen kurz zu halten | Kuerzere Pausen im Durchschnitt |

Unser Programm testet alle diese Strategien -- sowohl von HotSpot als auch von OpenJ9 -- und misst, welche unter den gegebenen Bedingungen am besten funktioniert.

---

## 4. Grundlagen: Was ist JIT-Kompilierung?

### Das Problem: Java startet langsam

Wenn ein Java-Programm startet, wird der Code zuerst nur **interpretiert** -- das heisst, die JVM liest jede Anweisung einzeln und fuehrt sie aus. Das ist wie ein Simultandolmetscher, der jeden Satz einzeln uebersetzt: funktioniert, aber langsam.

### Die Loesung: Just-In-Time Kompilierung

Nach einer Weile erkennt die JVM, welche Codestellen haeufig ausgefuehrt werden ("hot code"). Diese Stellen werden dann in **Maschinencode** uebersetzt -- also in die Sprache, die der Prozessor direkt versteht. Das ist viel schneller, als jedes Mal neu zu dolmetschen.

Dieser Vorgang heisst **JIT-Kompilierung** (Just-In-Time = "gerade rechtzeitig").

### Zwei Stufen

Die JVM hat zwei Uebersetzer (Compiler):
- **C1** (schneller Uebersetzer): Uebersetzt Code schnell, aber die Uebersetzung ist nicht perfekt optimiert. Ergebnis: Das Programm wird schnell etwas schneller.
- **C2** (gruendlicher Uebersetzer): Braucht laenger, erzeugt aber besseren Code. Ergebnis: Das Programm erreicht nach einer Aufwaermphase seine volle Geschwindigkeit.

### Warum ist das relevant?

Im Cloud-Betrieb werden Container (dazu gleich mehr) haeufig gestartet und gestoppt. Wenn ein Container nur kurz lebt, kommt der C2-Compiler gar nicht dazu, seine Arbeit zu beenden. In diesem Fall waere es besser, C2 ganz abzuschalten und nur C1 zu verwenden -- das Programm startet dann deutlich schneller.

Genau das testet eine unserer Konfigurationen: `tiered-stop-1` schaltet den langsamen, aber gruendlichen C2-Compiler ab.

### Die Alternativen: Native Image und CDS

Neben den ueblichen JIT-Ansaetzen gibt es noch zwei weitere Techniken, die unser Framework testet:

**GraalVM Native Image -- Vorher uebersetzen statt zur Laufzeit:**
Statt den Code waehrend des Laufens zu uebersetzen, wird hier das gesamte Programm **vor** dem Start in ein Maschinenprogramm umgewandelt. Das ist wie ein Buch, das vorher komplett uebersetzt wird, statt Seite fuer Seite beim Lesen. Vorteil: Extrem schneller Start (Millisekunden statt Sekunden). Nachteil: Die Uebersetzung ist nicht ganz so gut optimiert wie die eines C2-Compilers, der das Programm lange beobachten konnte.

**CDS (Class Data Sharing) -- Eine Abkuerzung beim Starten:**
Beim ersten Start merkt sich die JVM, welche Programmteile sie laden musste. Beim naechsten Start nutzt sie diese "Erinnerung" (ein Archiv), um schneller loszulegen. Das ist wie ein Koch, der sich am Vorabend alle Zutaten bereitlegt -- am naechsten Morgen geht das Kochen schneller.

---

## 5. Grundlagen: Was ist Docker?

### Das Problem: "Bei mir funktioniert's"

Ein haeufiges Problem in der Softwareentwicklung: Ein Programm laeuft auf dem Computer des Entwicklers, aber nicht auf dem Server. Der Grund: Unterschiedliche Betriebssystem-Versionen, fehlende Bibliotheken, andere Einstellungen.

### Die Loesung: Container

**Docker** verpackt ein Programm zusammen mit allem, was es braucht (Betriebssystem-Bibliotheken, Java-Version, Konfiguration) in einen **Container**. Dieser Container funktioniert ueberall gleich -- egal ob auf einem Laptop, einem Server oder in der Cloud.

Man kann sich einen Container vorstellen wie einen Umzugskarton: Alles ist sauber verpackt, beschriftet, und kann an jedem Ort ausgepackt und sofort benutzt werden.

### Ressourcenlimits

Ein wichtiger Aspekt von Docker: Man kann einem Container feste **Ressourcenlimits** geben:
- "Du bekommst maximal 1 CPU" (= eine Recheneinheit)
- "Du bekommst maximal 768 MB Arbeitsspeicher"

Das bildet die Realitaet in der Cloud nach, wo jeder Container nur einen begrenzten Anteil der Gesamtressourcen bekommt.

### Docker-Images: Verschiedene "Verpackungen"

Unser Benchmark-Programm verwendet **12 verschiedene Docker-Images** -- also 12 verschiedene Verpackungen fuer die gleiche Anwendung. Jedes Image enthaelt eine andere Java-Umgebung:

| Image | Inhalt | Wofuer? |
|-------|--------|---------|
| **jvm** | Standard-Java (HotSpot/Temurin) | Die meisten der 21 Flag-Tests |
| **openj9** | IBM-Java (OpenJ9/Semeru) | OpenJ9-Profile (P04, P06-P08, P10) |
| **native** | Vorher-uebersetztes Programm (GraalVM Native) | Profil P05: schnellstmoeglicher Start |
| **graalvm-jit** | GraalVM mit anderem JIT-Compiler | Profil P12: alternativer Compiler |
| **jvm-cds** | Standard-Java mit "Erinnerungs-Archiv" | Profil P11: schnellerer Start durch CDS |
| **jvm-vt** | Standard-Java mit virtuellen Threads | Profil P13: leichtgewichtige Threads (Project Loom) |

Jedes Image gibt es auch in einer Variante mit `-ek` am Ende (z.B. `jvm-ek`). Diese Variante enthaelt zusaetzlich die EBICS-Banking-Software, die fuer das Banking-Testszenario benoetigt wird.

### Warum ist das relevant?

Unser Benchmark startet fuer jede der 34 Konfigurationen einen **eigenen** Docker-Container mit exakt den gleichen Limits. So werden die Bedingungen fair und vergleichbar: Jede Konfiguration bekommt genau 1 CPU und 768 MB RAM -- nicht mehr, nicht weniger.

---

## 6. Grundlagen: Was ist die Cloud?

### Traditionell: Ein Server pro Anwendung

Frueher lief jede Anwendung auf einem eigenen physischen Server. Brauchte man mehr Kapazitaet, kaufte man einen groesseren Server. Problem: Die meiste Zeit war der Server nicht ausgelastet, kostete aber trotzdem Strom und Platz.

### Cloud: Viele Anwendungen teilen sich Hardware

In der Cloud teilen sich viele Anwendungen die gleiche Hardware. Jede Anwendung laeuft in einem Container mit definierten Ressourcenlimits. Ein **Orchestrator** (meistens Kubernetes) entscheidet, auf welchem Server welcher Container laeuft, und kann Container bei Bedarf starten, stoppen oder auf andere Server verschieben.

### Warum sind JVM-Einstellungen in der Cloud besonders wichtig?

1. **Kosten:** In der Cloud bezahlt man pro CPU und pro GB RAM. Wenn ein Container durch bessere JVM-Einstellungen mit weniger Speicher auskommt, spart das Geld.
2. **Startup-Zeit:** Wenn ploetzlich viele Anfragen kommen, muessen schnell neue Container gestartet werden (Autoscaling). Ein Container, der 10 Sekunden zum Starten braucht statt 3, reagiert zu langsam.
3. **Ressourcenlimits:** Container haben feste Grenzen. Der Garbage Collector muss innerhalb dieser Grenzen effizient arbeiten, sonst wird der Container vom System beendet (OOM-Kill = "Out of Memory", der Arbeitsspeicher ist voll).
4. **Dichte:** Je weniger Ressourcen ein Container braucht, desto mehr Container passen auf einen Server. Das erhoeht die Auslastung und senkt die Kosten.

---

## 7. Was macht das Programm konkret?

### Zwei Teile

Das Programm besteht aus zwei Teilen:

**Teil 1: Die Anwendung (System Under Test)**
Eine Spring-Boot-Webanwendung mit drei verschiedenen Aufgaben:
- **JSON:** 200.000 Datenobjekte erzeugen und als JSON zurueckgeben (stresst den Prozessor)
- **Alloc:** 10 Millionen kurzlebige Speicherbloecke erzeugen (stresst den Garbage Collector)
- **EBICS Upload:** Eine echte Bankueberweisung ueber das EBICS-Protokoll durchfuehren (realistischer Workload)

**Teil 2: Das Benchmark-Werkzeug**
Ein Kommandozeilen-Programm, das:
1. Fuer jede der 34 Konfigurationen einen Docker-Container startet
2. Die Anwendung darin hochfahren laesst
3. Erst 200 "Aufwaerm-Anfragen" schickt (damit die JVM sich aufwaermt)
4. Dann 500 "echte" Anfragen schickt und jede einzelne Antwortzeit misst
5. Waehrenddessen den CPU- und Speicherverbrauch aufzeichnet
6. Die Aufraeum-Protokolle (GC-Logs) aus dem Container ausliest und auswertet
7. Den Container stoppt und die Ergebnisse speichert
8. Das Ganze 3x wiederholt, jedes Mal in zufaelliger Reihenfolge

### Warum zufaellige Reihenfolge?

Wenn man immer in der gleichen Reihenfolge testet (erst A, dann B, dann C), koennte die letzte Konfiguration benachteiligt sein -- der Computer ist nach vielen Tests "waermer", der Prozessor drosselt vielleicht die Geschwindigkeit. Durch zufaellige Reihenfolge werden solche Verzerrungen vermieden.

### Warum 3 Wiederholungen?

Eine einzelne Messung kann zufaellig gut oder schlecht ausfallen. Durch 3 Wiederholungen kann man den **Durchschnitt** berechnen und sehen, wie stark die Ergebnisse **schwanken**. Je weniger sie schwanken, desto verlaesslicher sind sie (mehr dazu in Abschnitt 13: Statistik).

### Drei Geschwindigkeitsstufen

| Modus | Aufwaerm-Anfragen | Mess-Anfragen | Wiederholungen | Wofuer? |
|-------|------------------|--------------|----------------|---------|
| **Standard** | 200 | 500 | 3 | Die echten Ergebnisse fuer die Thesis |
| **Quick** (`--quick`) | 10 | 30 | 1 | Schnelltest: Funktioniert alles? |
| **Smoke** (`--smoke`) | 3 | 5 | 1 | Kuerzester Funktionstest: Startet Docker? Kommen Daten an? |

**Analogie:** Vor einem grossen Autorennen (Standard) faehrt man erst eine Proberunde (Quick), und davor prueft man nur, ob der Motor anspringt (Smoke).

### Ablauf eines einzelnen Runs (Schritt fuer Schritt)

```
1. Docker-Container starten
   -> Mit genau 1 CPU, 768 MB RAM, kein Swap
   -> JVM-Flags werden als Umgebungsvariable uebergeben

2. Warten, bis die Anwendung bereit ist
   -> Regelmaessig nachfragen: "Bist du schon da?"
   -> Die Wartezeit wird gemessen (= "Startup-Zeit")

3. Ruhe-Messung (IDLE)
   -> 3x CPU und Speicher messen, BEVOR Anfragen kommen
   -> Zeigt den Grundverbrauch

4. Erste Anfrage
   -> Die allererste Anfrage ist oft besonders langsam
   -> Wird separat gemessen ("First Request")

5. Aufwaermen (Warmup)
   -> 200 Anfragen schicken, Ergebnisse verwerfen
   -> Der JIT-Compiler optimiert waehrenddessen den Code

6. Mess-Anfragen
   -> 500 Anfragen schicken, JEDE Antwortzeit aufzeichnen
   -> Gleichzeitig: 10x CPU und Speicher messen (LOAD)

7. Ruhe-Messung nach Last (POST)
   -> 3x CPU und Speicher nach dem Test messen
   -> Zeigt, wie schnell sich die Anwendung erholt

8. GC-Log auslesen und auswerten
   -> Die Aufraeum-Protokolle des Containers werden gespeichert
   -> Automatische Analyse: Wie oft, wie lange, wie viel Speicher?

9. Container stoppen und entfernen

10. Ergebnisse speichern (CSV, JSON, Excel)
```

---

## 8. Die Zwei-Ebenen-Analyse: Warum 34 Konfigurationen?

Unser Benchmark hat **zwei verschiedene Fragestellungen**, die jeweils eine eigene "Ebene" von Tests erfordern:

### Ebene 1: "Welche Einstellung ist am besten?" (21 Konfigurationen)

Hier verwenden wir immer den **gleichen Motor** (HotSpot, die Standard-JVM) und drehen nur an den **Einstellungen** (Flags). Das ist wie ein Auto, bei dem man immer denselben Motor hat, aber die Motorsteuerung veraendert: Sportmodus, Eco-Modus, Turbo an/aus.

**Warum das wichtig ist:** So sieht man den Effekt jeder einzelnen Einstellung, ohne dass Unterschiede zwischen den JVM-Herstellern das Ergebnis verfaelschen.

### Ebene 2: "Welcher Motor ist am besten?" (13 Konfigurationen)

Hier tauschen wir den **Motor selbst** aus: Statt nur HotSpot zu verwenden, testen wir auch OpenJ9 (IBM), GraalVM Native Image, GraalVM JIT und Virtual Threads. Jede Konfiguration hat ein eigenes Docker-Image mit der jeweiligen JVM.

**Warum das wichtig ist:** In der Praxis wuerde man in der Cloud nicht nur Flags aendern, sondern auch die Frage stellen: "Sollten wir eine komplett andere JVM verwenden?"

### Zusammen: 21 + 13 = 34

Standardmaessig fuehrt das Programm **beide Ebenen** aus. Das ergibt bei 3 Wiederholungen insgesamt 102 einzelne Container-Runs.

---

## 9. Ebene 1: Die 21 Flag-Konfigurationen

Alle 21 verwenden dasselbe Docker-Image (HotSpot/Temurin). Der Unterschied liegt **nur** in den JVM-Flags.

### Gruppe 1: Welcher Aufraeumer ist der beste? (5 Varianten)

Dies sind fuenf verschiedene Garbage-Collection-Strategien. Jede hat einen anderen Ansatz, wie nicht mehr benoetigter Speicher freigegeben wird.

#### 1. baseline -- Der Standard
Keine besonderen Einstellungen. Java verwendet den Standard-Aufraeumer **G1 GC**. Das ist der Referenzpunkt: Alle anderen Konfigurationen werden mit dieser verglichen.

G1 teilt den Speicher in viele kleine Regionen auf und raeumt immer die Regionen auf, die am meisten Muell enthalten. Das Programm wird dabei kurz angehalten, aber die Pausen sind meist kuerzer als bei aelteren Methoden. Man kann sich das vorstellen wie einen Putzdienst, der immer zuerst das dreckigste Zimmer reinigt.

#### 2. zgc -- Der Schnelle ohne Unterbrechung
**Flag:** `-XX:+UseZGC`

ZGC (Z Garbage Collector) ist ein moderner Aufraeumer, der das Programm so gut wie **nie** anhalten muss. Er raeumt auf, waehrend das Programm normal weiterarbeitet. Die Pausen liegen im Bereich von unter einer Millisekunde (eine Tausendstelsekunde) -- egal wie viel Speicher verwaltet wird.

Der Preis: ZGC braucht etwas mehr Rechenleistung, weil er nebenbei arbeiten muss.

**Wann sinnvoll?** Wenn extrem schnelle und gleichmaessige Antwortzeiten wichtig sind -- zum Beispiel bei Online-Banking oder Echtzeit-Systemen.

#### 3. shenandoah -- Der Alternative ohne Unterbrechung
**Flag:** `-XX:+UseShenandoahGC`

Shenandoah verfolgt das gleiche Ziel wie ZGC (minimale Pausen), aber mit einer anderen Technik. Waehrend ZGC mit "farbigen Zeigern" arbeitet, verwendet Shenandoah "Weiterleitungszeiger". Beide erreichen aehnlich niedrige Pausen.

**Warum beide testen?** Um zu sehen, ob einer der beiden unter unseren spezifischen Bedingungen (1 CPU, 768 MB) besser abschneidet.

#### 4. parallel-gc -- Der Schnellarbeiter
**Flag:** `-XX:+UseParallelGC`

Der Parallel Collector haelt das Programm an, setzt dann aber **mehrere Aufraeumer gleichzeitig** ein, um moeglichst schnell fertig zu werden. Er ist optimiert auf **maximalen Durchsatz**: Die Gesamtzeit, die fuer Aufraeumarbeiten draufgeht, soll minimal sein.

Der Nachteil: Wenn er aufraeumt, steht das Programm komplett still -- und diese Pausen koennen laenger sein als bei G1.

**Wann sinnvoll?** Bei Hintergrund-Jobs, die moeglichst viele Aufgaben pro Stunde erledigen sollen und wo es nicht auf einzelne Antwortzeiten ankommt.

#### 5. serial-gc -- Der Minimalist
**Flag:** `-XX:+UseSerialGC`

Der Serial Collector verwendet nur **einen einzigen** Aufraeumer-Thread. Kein Koordinationsaufwand, keine Kommunikation zwischen Threads, minimaler Speicherverbrauch fuer die Aufraeum-Infrastruktur.

**Warum ist das interessant?** Unser Container hat nur 1 CPU. Aufraeumer mit mehreren Threads (wie G1 oder Parallel) muessen ihre Threads auf dieser einen CPU abwechselnd ausfuehren -- das kostet Koordinationsaufwand, der gar keinen Nutzen bringt, wenn sowieso nur ein Kern verfuegbar ist. Serial GC koennte in dieser Situation effizienter sein, weil er diesen Overhead nicht hat.

**Analogie:** In einer Einzimmerwohnung braucht man kein 5-koepfiges Reinigungsteam. Eine einzelne Reinigungskraft ist effizienter, weil sie sich nicht mit den anderen absprechen muss.

### Gruppe 2: Den Standard-Aufraeumer feintunen (3 Varianten)

Diese drei Konfigurationen verwenden alle den Standard-Aufraeumer G1, aber mit unterschiedlichen Einstellungen.

#### 6. g1-low-pause -- Kuerzere Pausen erzwingen
**Flag:** `-XX:+UseG1GC -XX:MaxGCPauseMillis=50`

Normalerweise versucht G1, Pausen unter 200 Millisekunden zu halten. Hier setzen wir das Ziel auf 50 Millisekunden. G1 raeumt dann haeufiger auf, aber jede einzelne Pause ist kuerzer.

**Analogie:** Statt einmal am Tag 20 Minuten zu putzen, putzt man viermal am Tag je 5 Minuten. Die Gesamtputzzeit kann etwas steigen, aber die Unterbrechungen sind kuerzer.

#### 7. g1-heap-256m -- Wenig Speicher erlauben
**Flag:** `-Xmx256m`

Normalerweise berechnet Java selbst, wie viel Speicher es verwenden soll. Hier sagen wir: "Du bekommst maximal 256 MB", obwohl der Container 768 MB hat.

**Warum?** Wenn die Anwendung mit 256 MB auskommt, koennte man in der Cloud kleinere (und billigere) Container verwenden. Der Aufraeumer muss allerdings haeufiger arbeiten, weil der Speicher schneller voll wird.

**Analogie:** Ein kleinerer Muelleimer muss oefter geleert werden, aber er nimmt weniger Platz in der Kueche ein.

#### 8. g1-heap-512m -- Mittlerer Speicher
**Flag:** `-Xmx512m`

Ein Mittelweg: Mehr als 256 MB, aber nicht der volle Container-Speicher. Zeigt, wie sich ein moderates Speicherlimit auswirkt.

### Gruppe 3: Interne Speicher-Optimierungen (2 Varianten)

#### 9. coops-off -- Groessere Zeiger verwenden
**Flag:** `-XX:-UseCompressedOops`

**Hintergrund:** Java verwendet auf 64-Bit-Systemen normalerweise "komprimierte Zeiger" -- das sind verkuerzte Adressen, die auf Daten im Speicher verweisen. Statt 8 Byte pro Adresse werden nur 4 Byte verwendet. Das spart erheblich Speicher.

Mit diesem Flag schalten wir diese Komprimierung **aus**. Jede Adresse belegt dann 8 Byte statt 4. Das Programm braucht mehr Speicher.

**Analogie:** Statt Kurzadressen wie "Goethestr. 5" verwendet man die volle Adresse "Goethestrasse Hausnummer 5, 1. Obergeschoss, Tuere links". Praeziser, aber braucht mehr Platz auf dem Briefumschlag.

**Warum testen?** Um zu **messen**, wie gross der Effekt der Zeiger-Komprimierung tatsaechlich ist. Das liefert eine Zahl, die in der Thesis zitiert werden kann.

#### 10. coh-on -- Kompaktere Objekt-Koepfe
**Flag:** `-XX:+UseCompactObjectHeaders`

**Hintergrund:** Jedes Java-Objekt hat einen internen "Kopf" (Object Header), der Verwaltungsinformationen enthaelt (Typ des Objekts, Hash-Code, GC-Status). Dieser Kopf ist normalerweise 12-16 Byte gross.

Dieses experimentelle Feature komprimiert den Kopf auf 8 Byte. Bei Programmen, die Millionen kleiner Objekte erzeugen (wie unsere JSON-Erzeugung mit 200.000 Objekten), spart das signifikant Speicher.

**Analogie:** Jedes Paket hat einen Aufkleber mit Absender, Empfaenger und Inhalt. Wenn man diesen Aufkleber halbiert, spart man bei Tausenden Paketen eine Menge Papier.

### Gruppe 4: Cloud-spezifische Einstellungen (2 Varianten)

#### 11. ram-percentage-75 -- Speicher prozentual zuweisen
**Flag:** `-XX:MaxRAMPercentage=75`

Seit Java 10 erkennt die JVM automatisch, in welchem Container sie laeuft und wie viel Speicher der Container hat. Mit diesem Flag sagt man: "Verwende maximal 75% des Container-Speichers fuer den Heap."

Bei unserem 768-MB-Container sind das ~576 MB. Der Rest (192 MB) bleibt fuer andere JVM-Komponenten (Metadaten, Thread-Speicher, JIT-Code-Cache).

**Warum 75%?** In der Praxis hat sich herausgestellt, dass 75% ein guter Wert ist. Bei 90% reicht der restliche Speicher oft nicht fuer die anderen JVM-Komponenten, und der Container wird vom System beendet.

**Analogie:** In einer Wohnung kann man nicht 100% der Flaeche mit Moebeln vollstellen -- man braucht auch Gaenge zum Durchgehen. 75% Moebel, 25% Gaenge ist ein guter Richtwert.

#### 12. tiered-stop-1 -- Schnellstart-Modus
**Flag:** `-XX:TieredStopAtLevel=1`

Dieses Flag schaltet den langsamen, aber gruendlichen C2-Compiler komplett ab (siehe Abschnitt 4: JIT-Kompilierung). Das Programm startet **deutlich schneller**, weil die aufwaendige C2-Optimierung entfaellt.

**Der Preis:** Das Programm erreicht nie seine volle Hoechstgeschwindigkeit, weil der C2-Compiler nie die aggressiven Optimierungen durchfuehrt.

**Wann sinnvoll?** Wenn Container haeufig gestartet und gestoppt werden -- zum Beispiel bei Autoscaling (automatisches Hochfahren neuer Instanzen bei Last) oder bei serverlosen Funktionen.

**Analogie:** Beim Sprint zieht man keine schweren Laufschuhe an, die erst nach 10 km bequem werden. Man nimmt leichte Schuhe, die sofort passen -- auch wenn sie fuer einen Marathon nicht ideal waeren.

### Gruppe 5: Flag-Kombinationen (9 Varianten)

In der Praxis verwendet man selten nur einen einzelnen Schalter. Oft kombiniert man mehrere Einstellungen. Diese 9 Konfigurationen testen solche Kombinationen:

#### 13. serial-gc-256m -- Der absolute Minimalist
**Flags:** `-XX:+UseSerialGC -Xmx256m`

Ein-Thread-Aufraeumer + kleiner Speicher. Der sparsamste Ansatz ueberhaupt. Wie ein Tiny House: extrem wenig Platz, aber fuer manche Zwecke voellig ausreichend.

**Wann sinnvoll?** Fuer winzige Container oder "Serverless Functions", die nur eine einzige Aufgabe erledigen und sofort wieder verschwinden.

#### 14. zgc-heap-512m -- Schnell und begrenzt
**Flags:** `-XX:+UseZGC -Xmx512m`

ZGC (fast keine Pausen) mit einem festen Speicherlimit von 512 MB. Gibt ZGC mehr Spielraum als die Standard-Automatik.

#### 15. shenandoah-heap-512m -- Alternative und begrenzt
**Flags:** `-XX:+UseShenandoahGC -Xmx512m`

Gleiche Idee wie #14, aber mit Shenandoah statt ZGC. Direkter Vergleich der beiden Pausen-minimierer unter gleichen Speicherbedingungen.

#### 16. tiered-stop-1-serial -- Der Sprinter
**Flags:** `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`

Schnellstart (kein C2-Compiler) + Ein-Thread-Aufraeumer. Optimiert fuer den schnellstmoeglichen Start bei minimalen Ressourcen.

**Analogie:** Ein Rennrad ohne Gepaecktraeger: leicht, schnell am Start, aber nicht fuer lange Touren mit viel Gepaeck gemacht.

#### 17. g1-coh-on -- Standard-Aufraeumer mit kompakten Koepfen
**Flags:** `-XX:+UseG1GC -XX:+UseCompactObjectHeaders`

G1 (Standard-Aufraeumer) kombiniert mit kompakteren Objekt-Koepfen. Ziel: Weniger Speicherverbrauch beim Standard-Aufraeumer, dadurch weniger Aufraeum-Druck.

#### 18. parallel-gc-256m -- Durchsatz mit wenig Speicher
**Flags:** `-XX:+UseParallelGC -Xmx256m`

Der Durchsatz-Aufraeumer mit nur 256 MB Speicher. Testet, ob Parallel GC unter Speicher-Knappheit noch Vorteile hat.

#### 19. g1-large-young -- Mehr Platz fuer Neues
**Flags:** `-XX:+UseG1GC -XX:NewRatio=1`

G1 mit einer groesseren "Young Generation" -- das ist der Bereich, in dem neue Daten landen. Normalerweise bekommt dieser Bereich etwa ein Drittel des Speichers. Hier geben wir ihm die Haelfte.

**Analogie:** In einem Buero gibt man dem Posteingangskorb mehr Platz, damit er nicht so schnell ueberlaeuft und weniger oft geleert werden muss.

**Wann sinnvoll?** Bei Programmen, die sehr viele kurzlebige Daten erzeugen (wie unser Alloc-Szenario mit 10 Millionen kurzlebigen Objekten).

#### 20. zgc-tiered-stop-1 -- Schnell starten, schnell antworten
**Flags:** `-XX:+UseZGC -XX:TieredStopAtLevel=1`

ZGC (minimale Pausen) kombiniert mit abgeschaltetem C2-Compiler (schneller Start). Optimiert fuer Container, die schnell starten UND gleichmaessig schnelle Antwortzeiten liefern sollen.

**Wann sinnvoll?** Fuer Serverless-Funktionen oder Autoscaling-Container, wo sowohl schneller Start als auch niedrige Latenzen wichtig sind.

#### 21. zgc-coh-on -- Schnell antworten mit kompakten Koepfen
**Flags:** `-XX:+UseZGC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders`

ZGC (minimale Pausen) kombiniert mit kompakteren Objekt-Koepfen. ZGC ist bekannt dafuer, viel Speicher zu beanspruchen. Durch kleinere Objekt-Koepfe wird das Live-Set kompakter, was ZGC weniger Speicher-Overhead verursacht und moeglicherweise die GC-Haeufigkeit reduziert.

**Wann sinnvoll?** Wenn man die Pausen-Vorteile von ZGC nutzen moechte, aber den Speicherverbrauch etwas senken will.

---

## 10. Ebene 2: Die 13 Laufzeitprofile

Waehrend Ebene 1 immer denselben Motor (HotSpot) mit verschiedenen Einstellungen testet, tauscht Ebene 2 den **Motor selbst** aus. Jedes Profil repraesentiert eine typische Cloud-Strategie.

### HotSpot-Profile (Standard-Java)

#### P01 -- Standard-Cloud-Deployment
**Image:** jvm | **Flags:** `-XX:+UseG1GC -XX:MaxRAMPercentage=75`

Die "Best Practice"-Konfiguration, wie man sie haeufig in Cloud-Deployments findet: G1 als Aufraeumer, 75% des Container-Speichers fuer Java. Das ist der Referenzpunkt fuer alle anderen Profile.

#### P02 -- Schnellstart-Deployment
**Image:** jvm | **Flags:** `-XX:+UseG1GC -XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=75`

Wie P01, aber mit abgeschaltetem C2-Compiler. Fuer Container, die haeufig neu gestartet werden.

#### P03 -- Latenz-optimiertes Deployment
**Image:** jvm | **Flags:** `-XX:+UseZGC -XX:MaxRAMPercentage=75`

ZGC statt G1 fuer minimale Pausen. Fuer Services, die strengste Anforderungen an Antwortzeiten haben.

#### P09 -- HotSpot mit wenig Speicher
**Image:** jvm | **Flags:** `-XX:+UseG1GC -Xmx256m`

HotSpot mit nur 256 MB Speicher. Wird direkt mit P10 (OpenJ9 mit 256 MB) verglichen: Welche JVM kommt besser mit wenig Speicher zurecht?

### OpenJ9-Profile (IBM-Java)

Eclipse OpenJ9 ist eine alternative JVM von IBM. Sie ist bekannt dafuer, oft **weniger Speicher** zu verbrauchen als HotSpot.

#### P04 -- OpenJ9 Standard
**Image:** openj9 | **Flags:** `-XX:MaxRAMPercentage=75`

OpenJ9 mit dem Standard-Aufraeumer "gencon" (generational concurrent). Direkter Vergleich zu P01 (HotSpot Standard).

#### P06 -- OpenJ9 Balanced
**Image:** openj9 | **Flags:** `-Xgcpolicy:balanced -XX:MaxRAMPercentage=75`

Der "balanced"-Aufraeumer teilt den Speicher in Zonen auf und raeumt dort auf, wo am meisten Muell liegt -- aehnlich wie G1 bei HotSpot.

#### P07 -- OpenJ9 Durchsatz
**Image:** openj9 | **Flags:** `-Xgcpolicy:optthruput -XX:MaxRAMPercentage=75`

Optimiert auf maximalen Durchsatz. Raeumt seltener auf, dafuer koennen die Pausen laenger sein.

#### P08 -- OpenJ9 Kurze Pausen
**Image:** openj9 | **Flags:** `-Xgcpolicy:optavgpause -XX:MaxRAMPercentage=75`

Optimiert auf kurze durchschnittliche Pausen. Raeumt oefter nebenbei auf.

#### P10 -- OpenJ9 mit wenig Speicher
**Image:** openj9 | **Flags:** `-Xmx256m`

OpenJ9 mit nur 256 MB Speicher. Direkter Vergleich zu P09 (HotSpot mit 256 MB): Welcher JVM-Hersteller geht effizienter mit wenig Speicher um?

### Spezial-Profile

#### P05 -- GraalVM Native Image
**Image:** native | **Flags:** *(keine)*

Hier laeuft kein Java-Programm im klassischen Sinn. Die gesamte Anwendung wurde **vor** dem Start in ein Maschinenprogramm umgewandelt. Es gibt keine JVM, keinen JIT-Compiler, kein Klassenladen.

**Erwartung:**
- Startup: extrem schnell (Millisekunden statt Sekunden)
- Speicher: deutlich weniger
- Durchsatz: etwas geringer (kein C2-Compiler, der zur Laufzeit optimiert)
- GC-Logs: nicht verfuegbar (keine klassische JVM)

**Analogie:** Statt ein Buch Wort fuer Wort zu uebersetzen, waehrend man es vorliest, hat man das gesamte Buch vorher uebersetzt und liest nur noch die fertige Uebersetzung vor. Viel schneller am Start, aber die Uebersetzung ist fix -- sie kann nicht mehr an den Zuhoerer angepasst werden.

#### P11 -- HotSpot mit CDS
**Image:** jvm-cds | **Flags:** `-XX:+UseG1GC -XX:MaxRAMPercentage=75`

HotSpot mit **Class Data Sharing (CDS)**. Beim Bauen des Docker-Images wird die Anwendung einmal gestartet, und die JVM merkt sich, welche Klassen sie laden musste. Beim echten Start nutzt sie dieses "Gedaechtnis" (ein Archiv), um schneller loszulegen.

**Analogie:** Ein Koch, der sich am Vorabend alle Zutaten bereitlegt, die Messer schaerft und die Rezepte bereitlegt. Am naechsten Morgen kann er sofort loslegen, statt erst alles zusammenzusuchen.

**Erwartung:** Schnellerer Startup als P01, gleicher Peak-Durchsatz.

#### P12 -- GraalVM JIT
**Image:** graalvm-jit | **Flags:** `-XX:+UseG1GC -XX:MaxRAMPercentage=75`

Statt des normalen C2-Compilers verwendet diese JVM den **GraalVM JIT-Compiler**. Dieser Compiler kann in manchen Situationen besseren Maschinencode erzeugen als der Standard-C2-Compiler.

**Analogie:** Gleicher Motor, aber ein anderer, modernerer Turbolader -- manchmal bringt er mehr Leistung, manchmal keinen Unterschied.

#### P13 -- HotSpot mit Virtual Threads (Project Loom)
**Image:** jvm-vt | **Flags:** `-XX:+UseG1GC -XX:MaxRAMPercentage=75`

Dieses Profil verwendet **Virtual Threads** (auch bekannt als "Project Loom"). Statt der klassischen "Platform Threads", bei denen jeder Thread einem Betriebssystem-Thread entspricht, nutzt die JVM hier extrem leichtgewichtige, virtuelle Threads. Tausende davon koennen auf wenigen Betriebssystem-Threads laufen.

Konkret wird der eingebettete Tomcat-Webserver so konfiguriert, dass er fuer jede HTTP-Anfrage einen Virtual Thread startet statt einen aus einem festen Thread-Pool zu nehmen. Der Vorteil: Bei vielen gleichzeitigen Anfragen (z.B. mit I/O-Wartezeiten wie bei EBICS) blockiert ein wartender Thread keine teure Betriebssystem-Ressource.

**Analogie:** Statt fuer jede Aufgabe einen fest angestellten Mitarbeiter zu haben, der waehrend der Wartezeit untaetig herumsitzt, stellt man "Zeitarbeiter" ein, die kommen, ihre Aufgabe erledigen, und waehrenddessen den Platz fuer andere freimachen, wenn sie gerade auf etwas warten.

**Erwartung:** Aehnliche oder bessere Latenz und Durchsatz bei I/O-lastigen Workloads (EBICS), weniger Speicherverbrauch fuer Thread-Stacks.

---

## 11. Was wird gemessen?

Fuer **jede** der 34 Konfigurationen werden exakt die **gleichen** Messwerte erhoben. Es wird nichts weggelassen oder unterschiedlich behandelt. Das macht die Ergebnisse direkt vergleichbar.

### Startup-Zeit (Readiness)
**Was:** Wie lange dauert es vom Starten des Containers bis das Programm bereit ist, Anfragen zu beantworten?
**Einheit:** Millisekunden
**Warum wichtig:** In der Cloud muessen neue Instanzen schnell verfuegbar sein. Eine Startup-Zeit von 10 Sekunden statt 3 bedeutet, dass Nutzer 7 Sekunden laenger warten muessen, wenn gerade hochskaliert wird.

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
- **Mean:** Der arithmetische Durchschnitt. Kann durch einzelne Ausreisser verfaelscht werden, deshalb sind Perzentile aussagekraeftiger.

**Beispiel:** p50 = 0.02s, p99 = 0.15s bedeutet: Die Haelfte aller Anfragen ist in 20 Millisekunden beantwortet, aber die langsamsten 1% brauchen 150 Millisekunden. Ein Garbage Collector, der seltene, aber lange Pausen verursacht, zeigt sich im p99-Wert.

**Analogie fuer Perzentile:** In einem Restaurant mit 100 Gaesten: Der p50-Wert ist die Wartezeit, nach der die Haelfte der Gaeste bereits bedient wurde. Der p99-Wert ist die Wartezeit des Gastes, der fast als Letzter drankommt. Wenn der p99 sehr hoch ist, hat das Restaurant ein Problem mit einzelnen Gaesten, die extrem lange warten.

### Durchsatz (Throughput)
**Was:** Wie viele Anfragen pro Sekunde kann die Anwendung verarbeiten?
**Einheit:** Requests pro Sekunde (req/s)
**Berechnung:** 500 Mess-Requests / Gesamtdauer der Messphase
**Warum wichtig:** Direktes Mass fuer die Kapazitaet. Ein Container mit 100 req/s schafft doppelt so viele Nutzer wie einer mit 50 req/s. In der Cloud bedeutet hoeherer Durchsatz: weniger Container noetig = niedrigere Kosten.

### CPU-Auslastung
**Was:** Wie viel Prozent der zugewiesenen CPU nutzt der Container?
**Einheit:** Prozent
**Phasen:**
- **IDLE:** CPU-Verbrauch im Ruhezustand (vor dem Test). Zeigt den Grundverbrauch durch JVM-Hintergrundprozesse.
- **LOAD:** CPU-Verbrauch waehrend der 500 Mess-Anfragen. Zeigt den Verbrauch unter Last.
- **POST:** CPU-Verbrauch direkt nach dem Test. Zeigt, ob der Garbage Collector noch aufraeumt.

### Speicherauslastung
**Was:** Wie viel Prozent des zugewiesenen Speichers (768 MB) nutzt der Container?
**Einheit:** Prozent
**Phasen:** Gleich wie bei CPU (IDLE, LOAD, POST).
**Warum wichtig:** Ein Container, der 90% Speicher nutzt, ist am Limit. Bei kurzzeitigen Lastspitzen kann er den Container-Kill-Schwellwert ueberschreiten. Ein Container mit 50% hat genug Puffer.

### GC-Metriken (Aufraeum-Statistiken)
**Was:** Wie oft und wie lange hat der Garbage Collector aufgeraeumt?
Details dazu im naechsten Abschnitt.

---

## 12. GC-Log-Auswertung: Dem Aufraeumer ueber die Schulter schauen

### Was sind GC-Logs?

Wenn die JVM den Garbage Collector startet, kann sie ein Protokoll darueber schreiben: Wann wurde aufgeraeumt? Wie lange hat es gedauert? Wie viel Speicher wurde freigegeben? Diese Protokolle heissen **GC-Logs** (Garbage Collection Logs).

Unser Benchmark aktiviert diese Protokollierung automatisch fuer jeden Container und wertet sie am Ende automatisch aus.

**Analogie:** Es ist wie ein Reinigungsprotokoll in einem Hotel. Jedes Mal, wenn der Zimmerservice kommt, wird notiert: Uhrzeit, Dauer, welche Zimmer gereinigt wurden. Am Ende des Tages kann man auswerten, wie oft und wie lange geputzt wurde.

### Was wird aus den GC-Logs herausgelesen?

| Messwert | Bedeutung | Einfach erklaert |
|----------|-----------|-----------------|
| **Anzahl GC-Pausen** | Wie oft musste das Programm fuer Aufraeumarbeiten angehalten werden? | Wie oft musste das Restaurant kurz schliessen? |
| **Anzahl Full GCs** | Wie oft musste der Aufraeumer den GESAMTEN Speicher durchsuchen? | Wie oft musste das gesamte Hotel auf einmal gereinigt werden? (Sehr teuer!) |
| **Gesamte Pausenzeit** | Wie viel Zeit wurde insgesamt fuer Pausen aufgewendet? | Wie viele Minuten war das Restaurant insgesamt geschlossen? |
| **Laengste Pause** | Wie lang war die laengste einzelne Unterbrechung? | Was war die laengste Schliessung am Stueck? |
| **GC-Overhead** | Wie viel Prozent der Gesamtzeit wurde fuer Aufraeuemen aufgewendet? | Wie viel Prozent des Arbeitstages wurde geputzt statt Gaeste bedient? |
| **Peak Heap** | Wie voll war der Speicher maximal nach dem Aufraeuemen? | Wie voll war der Muelleimer selbst NACH dem Leeren? |

### Zwei verschiedene Log-Formate

Weil HotSpot und OpenJ9 ihre GC-Logs unterschiedlich aufschreiben, hat unser Programm **zwei verschiedene Auswerter**:

- **HotSpot-Parser:** Liest das "Unified Logging"-Format (Textzeilen mit Zeitstempeln)
- **OpenJ9-Parser:** Liest das XML-basierte Format von IBM (verschachtelte XML-Tags)

Fuer GraalVM Native Images (P05) gibt es keine GC-Logs, weil dort keine klassische JVM laeuft.

### Wo landen die Rohdaten?

Die vollstaendigen GC-Logs werden als Dateien gespeichert (`bench-results/gc-logs/`). Man kann sie auch mit externen Werkzeugen wie GCViewer oder GCEasy weiter analysieren -- zum Beispiel um detaillierte Grafiken der Speichernutzung ueber die Zeit zu erstellen.

---

## 13. Statistik: Wie verlaesslich sind die Ergebnisse?

### Das Problem: Schwankungen

Kein Benchmark liefert zweimal exakt dasselbe Ergebnis. Der Prozessor ist unterschiedlich ausgelastet, der Garbage Collector startet zu leicht unterschiedlichen Zeitpunkten, das Betriebssystem fuehrt Hintergrundaufgaben aus. Deshalb braucht man **mehrere Messungen** und statistische Methoden, um zu beurteilen, wie verlaesslich ein Ergebnis ist.

### Mittelwert und Standardabweichung

Bei 3 Wiederholungen berechnet unser Programm:
- **Mittelwert:** Der Durchschnitt aller 3 Messungen. Beispiel: 5.0s + 5.2s + 4.8s = Mittelwert 5.0s
- **Standardabweichung:** Wie stark schwanken die Werte um den Mittelwert? Je kleiner die Standardabweichung, desto stabiler die Messung.

**Analogie:** Wenn ein Schuetze dreimal schiesst und alle drei Schuesse nah beieinander liegen, ist er konstant (kleine Standardabweichung). Wenn die Schuesse weit verstreut sind, ist er unberechenbar (grosse Standardabweichung).

### Konfidenzintervall: "Wie sicher sind wir?"

Ein 95%-Konfidenzintervall sagt: "Wir sind zu 95% sicher, dass der wahre Wert innerhalb dieses Bereichs liegt."

**Beispiel:** Mittelwert = 5.0s, Konfidenzintervall = +/- 0.3s. Das bedeutet: Der wahre Wert liegt mit 95% Wahrscheinlichkeit zwischen 4.7s und 5.3s.

**Warum ist das wichtig?** Wenn zwei Konfigurationen Mittelwerte von 5.0s und 5.2s haben, aber ihre Konfidenzintervalle sich ueberlappen, dann ist der Unterschied **nicht statistisch gesichert** -- er koennte auch Zufall sein. Nur wenn die Intervalle sich NICHT ueberlappen, kann man sagen: "Konfiguration A ist wirklich schneller als B."

### Fehlerbalken in den Diagrammen

In den Excel-Diagrammen sieht man bei den Balken kleine **Linien nach oben und unten** -- die sogenannten Fehlerbalken. Diese zeigen das 95%-Konfidenzintervall an:

```
    |
    |    T         <- Fehlerbalken oben
    |  |----|
    |  |    |
    |  |    |      <- Der Balken selbst (Mittelwert)
    |  |    |
    |  |----|
    |    L         <- Fehlerbalken unten
    +----------
```

**Wie liest man das?** Je laenger die Fehlerbalken, desto unsicherer die Messung. Kurze Fehlerbalken = stabile, verlaessliche Ergebnisse.

---

## 14. Wie liest man die Ergebnisse?

Die Ergebnisse werden in Excel-Dateien gespeichert. Es gibt zwei Arten:

### Einzelrun-Excel (7 Sheets)

Nach einem Benchmark-Lauf entsteht eine Excel-Datei mit 7 Tabs:

#### Sheet 1: Uebersicht
Eine grosse Tabelle mit **allen** Messwerten fuer **alle** Konfigurationen. Jede Zeile ist ein einzelner Testlauf. Die Spalten sind in Gruppen eingeteilt:
- **Konfiguration:** Name, Docker-Image, JVM-Flags
- **Startup:** Readiness-Zeit, First Request
- **Latenzen:** p50, p95, p99, Mean
- **Durchsatz:** Requests pro Sekunde
- **Docker LOAD:** CPU%, Mem% waehrend Last
- **GC-Metriken:** Pausenanzahl, Pausendauer, Overhead, Peak Heap
- **Messprofil:** Wie viele Warmup/Mess-Requests
- **Meta:** Wiederholungsnummer, verwendeter Readiness-Check

Die Tabelle hat **Filter**, mit denen man nach bestimmten Konfigurationen filtern kann.

#### Sheet 2: Latenzen
Ein **Balkendiagramm**, das fuer jede Konfiguration die Perzentile (p50, p95, p99) nebeneinander zeigt:
- **Gruene Balken:** p50 (Median, typische Latenz)
- **Orange Balken:** p95 (die meisten Anfragen)
- **Rote Balken:** p99 (Worst-Case)

**Wie lesen:** Je kuerzer die Balken, desto besser. Wenn die roten Balken deutlich laenger sind als die gruenen, verursacht der GC gelegentliche lange Pausen. Die Fehlerbalken zeigen, wie stabil die Messungen waren.

#### Sheet 3: Startup & Throughput
Zwei Diagramme:
1. **Startup-Zeit:** Balken pro Konfiguration. Kuerzer = schneller startbereit.
2. **Durchsatz:** Balken pro Konfiguration. Laenger = mehr Anfragen pro Sekunde = besser.

Beide Diagramme enthalten Fehlerbalken (Konfidenzintervalle).

#### Sheet 4: Ressourcen
Ein Diagramm mit CPU- und Speicherverbrauch unter Last:
- **Orange Balken:** CPU% waehrend LOAD
- **Dunkelblaue Balken:** Mem% waehrend LOAD

**Wie lesen:** Niedrigere Werte bedeuten effizientere Ressourcennutzung. Hohe Speicherwerte (nahe 100%) sind kritisch.

#### Sheet 5: Rohdaten
Alle 500 Einzellatenzen jeder Konfiguration. Gedacht fuer eigene statistische Auswertungen (z.B. Histogramme, Verteilungsanalysen).

#### Sheet 6: GC-Zusammenfassung
Eine Tabelle mit allen GC-Metriken (Pausenanzahl, Dauer, Overhead, Peak Heap) plus ein **Balkendiagramm** mit logarithmischer Skala.

**Warum logarithmisch?** Weil die Unterschiede zwischen den GCs enorm sein koennen: Serial GC hat vielleicht 50 Pausen, waehrend ZGC nur 2 hat. Auf einer normalen Skala waere der ZGC-Balken unsichtbar klein. Die logarithmische Skala macht beide Werte sichtbar vergleichbar.

**Analogie:** Wenn man die Groessen eines Gartens (100 qm), eines Parks (10.000 qm) und einer Stadt (100.000.000 qm) auf einem Diagramm darstellen will, wuerde der Garten auf einer normalen Skala unsichtbar sein. Die logarithmische Skala zeigt alle drei sinnvoll nebeneinander.

#### Sheet 7: GC-Timeline
Ein **Verlaufsdiagramm**, das die GC-Pausen ueber die Zeit zeigt. Jede Konfiguration hat eine eigene Linie. Man sieht, wann Pausen auftreten und wie lang sie sind.

**Wie lesen:** Viele hohe Ausschlaege = haeufige, lange Pausen. Eine flache Linie = wenige oder kurze Pausen.

### Merge-Excel: Mehrere Laeufe vergleichen (6 Sheets)

Wenn man den Benchmark mehrmals ausgefuehrt hat (z.B. einmal mit JSON, einmal mit Alloc), kann man die Ergebnisse mit `--merge-excel` zusammenfuehren. Die Merge-Excel hat 6 Sheets:

| Sheet | Inhalt |
|-------|--------|
| **Uebersicht alle Runs** | Alle Laeufe in einer Tabelle |
| **Latenzen alle Runs** | Latenz-Vergleich mit Fehlerbalken |
| **Startup alle Runs** | Startup/Durchsatz-Vergleich mit Fehlerbalken |
| **Ressourcen alle Runs** | Ressourcen- + GC-Vergleich mit Fehlerbalken |
| **Zusammenfassung** | Mittelwerte mit Standardabweichung |
| **Ranking** | Alle Konfigurationen relativ zur Baseline (= 100%) |

Das **Ranking-Sheet** ist besonders nuetzlich: Es zeigt alle Messwerte als Prozentwert relativ zur Baseline. Beispiel: Wenn die Baseline eine Startup-Zeit von 5s hat und eine Konfiguration 2.5s, steht dort "50%". Man sieht sofort, welche Konfigurationen besser oder schlechter als der Standard sind.

### Farbkodierung in den Profil-Diagrammen

In den Diagrammen der Laufzeitprofile (Ebene 2) sind die Balken farblich kodiert:
- **Blau:** HotSpot-Profile
- **Tuerkis:** OpenJ9-Profile
- **Orange:** Native-Image-Profil

So sieht man auf einen Blick, welcher JVM-Hersteller in welchem Bereich vorne liegt.

---

## 15. Was kann man mit den Ergebnissen anfangen?

### Empfehlungen ableiten

Nach dem Benchmark hat man Daten, um fundierte Empfehlungen zu geben:

**Beispiel-Ergebnisse (hypothetisch):**

| Konfiguration | Startup | p95-Latenz | Durchsatz | Speicher |
|---------------|---------|-----------|-----------|---------|
| P01-hotspot-standard | 5.2s | 0.08s | 120 req/s | 65% |
| P03-hotspot-low-latency | 5.8s | 0.03s | 105 req/s | 70% |
| P05-native | 0.1s | 0.06s | 90 req/s | 35% |
| P04-openj9-low-memory | 6.1s | 0.09s | 110 req/s | 50% |
| tiered-stop-1-serial | 2.1s | 0.10s | 85 req/s | 55% |
| serial-gc-256m | 4.5s | 0.12s | 95 req/s | 30% |

**Moegliche Schlussfolgerungen:**
- Fuer **latenz-kritische Services** (z.B. Online-Banking): P03 mit ZGC -- niedrigste p95-Latenz
- Fuer **kurzlebige Container** (Autoscaling/Serverless): P05 Native Image -- 50x schnellerer Start
- Fuer **minimalen Speicher** (hohe Container-Dichte): serial-gc-256m oder P05 Native -- wenigste Ressourcen
- Fuer **Allrounder**: P01 HotSpot Standard -- bester Kompromiss
- Fuer **schnellen Start bei JVM-basierten Apps**: tiered-stop-1-serial -- schnellster JVM-Start

### In der Thesis

Die Ergebnisse liefern **messbare Belege** fuer Empfehlungen zur Container-Konfiguration. Statt "ZGC sollte niedrigere Latenzen haben" kann man schreiben: "ZGC reduziert die p95-Latenz im JSON-Szenario um 62% gegenueber der Baseline (0.03s vs. 0.08s) bei einem Throughput-Rueckgang von 12.5%."

Die GC-Log-Auswertung liefert zusaetzliche Einblicke: "Der GC-Overhead von Serial GC betraegt 8.2%, waehrend ZGC nur 0.3% erreicht -- bei allerdings 15% hoeherem CPU-Verbrauch."

Das Konfidenzintervall macht die Aussagen belastbar: "Der Unterschied in der Startup-Zeit zwischen CDS und Standard ist statistisch signifikant (95%-KI: 1.2s +/- 0.15s vs. 5.0s +/- 0.3s)."

---

## 16. GraalVM Native Image: Welche Probleme mussten geloest werden?

In Abschnitt 4 und beim Profil P05 wurde bereits erklaert, **was** GraalVM Native Image ist: Das gesamte Programm wird vor dem Start in Maschinencode uebersetzt -- wie ein Buch, das komplett uebersetzt wird, bevor man es vorliest. Die Vorteile: extrem schneller Start, weniger Speicherverbrauch.

Was dort nicht erklaert wurde: Die Umwandlung unserer Banking-Anwendung in ein Native Image war eines der technisch anspruchsvollsten Probleme des gesamten Projekts. Drei voneinander unabhaengige Probleme mussten geloest werden, bevor der EBICS-Banking-Endpunkt im Native Image funktionierte.

### 16.1 Das Grundproblem: Alles muss vorher bekannt sein

Bei einer normalen JVM kann das Programm zur Laufzeit sagen: "Ich brauche jetzt die Klasse `CryptoHelper` -- bitte lade sie." Das ist wie ein Koch, der waehrend des Kochens zum Regal geht und eine Zutat holt, die er gerade braucht.

Bei einem Native Image geht das **nicht**. Alles, was das Programm jemals brauchen koennte, muss **vor** dem Uebersetzen bekannt sein. Das ist wie ein Koch, der in einen Raum gesperrt wird: Alles, was auf der Arbeitsplatte steht, kann er verwenden. Aber er kann nicht mehr zum Regal gehen. Wenn eine Zutat fehlt, schlaegt das Rezept fehl.

Unsere EBICS-Banking-Anwendung nutzt intensiv Techniken, die Klassen erst zur Laufzeit laden:
- **Kryptographie** (Verschluesselung, digitale Signaturen): Der Algorithmus wird per Name angefordert ("Gib mir den Verschluesseler namens PBES2")
- **XML-Verarbeitung** (EBICS-Nachrichten sind XML): Schema-Dateien werden dynamisch geladen
- **Reflection** (ein Programm schaut sich selbst an): Der EBICS-Kernel laedt Hunderte von Klassen ueber ihren Namen

Fuer all das mussten wir dem Native-Image-Builder vorher mitteilen, welche Klassen benoetigt werden. Die dafuer noetige Konfiguration stammt aus drei Quellen:
1. **Spring AOT:** Spring Boot erkennt automatisch seine eigenen Klassen (Controller, Services etc.)
2. **Tracing Agent:** Ein spezielles Werkzeug, das die Anwendung einmal normal startet, dabei aufzeichnet, welche Klassen geladen werden, und eine Liste erstellt
3. **Eigene Build-Zeit-Logik:** Eine speziell geschriebene Klasse (`IaikSecurityFeature`), die waehrend des Uebersetzens zusaetzliche Registrierungen vornimmt

Doch selbst mit einer vollstaendigen Liste aller Klassen gab es drei Probleme, die spezielle Loesungen erforderten.

### 16.2 Problem 1: Der Sicherheitsausweis wird nicht akzeptiert

**Worum geht es?**

Java hat ein eingebautes Sicherheitssystem fuer Verschluesselung. Jeder Kryptographie-Anbieter (in unserem Fall die Bibliothek "IAIK") muss einen "Sicherheitsausweis" vorweisen -- ein digitales Zertifikat, das von einer vertrauenswuerdigen Stelle ausgestellt wurde. Java prueft diesen Ausweis beim ersten Verwendungsversuch.

**Was ging schief?**

Die IAIK-Bibliothek hat **keinen** Ausweis, der von Javas eingebauter Vertrauensstelle akzeptiert wird. Bei einer normalen JVM ist das kein Problem -- dort wird die Pruefung einfach uebersprungen. Aber beim Native Image wird das Ergebnis der Pruefung **waehrend des Uebersetzens** in das fertige Programm "eingebrannt". Und das eingebrannte Ergebnis lautete: "Pruefung fehlgeschlagen."

Jedes Mal, wenn das fertige Programm spaeter versuchte, etwas zu verschluesseln, fand es die eingebrannte Fehlermeldung und verweigerte die Arbeit.

**Analogie:** Man stelle sich ein Hotel vor, in dem jeder Gast beim Einchecken seinen Ausweis zeigen muss. IAIK zeigt seinen Ausweis, aber die Rezeption erkennt ihn nicht an -- "Diese Behoerde kennen wir nicht." In einem normalen Hotel (JVM) wuerde der Manager sagen: "Lassen Sie ihn trotzdem rein." Aber beim Native Image wird die Ablehnung in Stein gemeisselt: Am Eingang steht fuer immer "Zutritt verweigert fuer IAIK."

**Die Loesung:** Wir haben ein spezielles Programmstueck geschrieben, das **waehrend des Uebersetzens** ausgefuehrt wird. Es oeffnet den internen Karteikasten der Sicherheitspruefung, findet den Eintrag "IAIK: abgelehnt" und ersetzt ihn durch "IAIK: zugelassen". Wenn das Ergebnis dann in das fertige Programm eingebrannt wird, steht dort: "IAIK ist vertrauenswuerdig." Problem geloest.

### 16.3 Problem 2: Der Werkzeugkasten wird ausgeraeumt

**Worum geht es?**

Die IAIK-Bibliothek stellt 408 verschiedene kryptographische Werkzeuge bereit: Verschiedene Verschluesselungsverfahren, Hash-Algorithmen, digitale Signaturen und vieles mehr. Man kann sich das vorstellen wie einen riesigen Werkzeugkasten mit 408 Fachern -- Schraubenzieher, Zangen, Saegen, Bohrer in allen Groessen und Varianten.

**Was ging schief?**

GraalVM ist sehr aggressiv beim "Aufraeumen": Alles, was waehrend des Uebersetzens nicht benutzt wird, wird aus dem fertigen Programm entfernt. Das Ziel ist ein kleines, schlankes Programm. Das Problem: Waehrend des Uebersetzens laeuft kein Anwendungscode. Niemand ruft `Cipher.getInstance("PBES2")` auf. Also entschied GraalVM: "PBES2 wird nie gebraucht -- weg damit."

Genau dieses PBES2-Verfahren wird aber spaeter gebraucht, um die Schluesseldateien fuer die Bankverbindung zu oeffnen. Das Ergebnis: "Algorithm PBES2 not available" -- die Banking-Funktion war defekt.

**Analogie:** Man bereitet sich auf einen Umzug vor und ein eifriger Helfer raumt den Werkzeugkasten aus: "Schraubenzieher Groesse 3 habe ich dich noch nie benutzen sehen -- brauchen wir nicht!" Am neuen Ort stellt man fest, dass genau diese Groesse fuer die Kueche gebraucht wird. Aber der Schraubenzieher liegt im Muell, 100 Kilometer entfernt.

**Die Loesung:** Unser spezielles Build-Zeit-Programm nimmt waehrend des Uebersetzens **jedes einzelne der 408 Werkzeuge** einmal in die Hand. Es ruft fuer jedes Werkzeug die offizielle Java-Schnittstelle auf: `Cipher.getInstance("PBES2")`, `Mac.getInstance("HmacSHA256")`, und so weiter fuer alle 408. Dadurch sieht GraalVM: "Oh, dieses Werkzeug wird benutzt -- das muss bleiben." Am Ende bleiben 407 von 408 Werkzeugen erhalten. (Eines -- `SecureRandom` -- wird absichtlich uebersprungen, weil es ein anderes Problem verursachen wuerde, siehe naechster Abschnitt.)

### 16.4 Randbedingung: Keine Zufallszahlen einfrieren

Eines der 408 Werkzeuge (`SecureRandom`) konnte nicht wie die anderen behandelt werden. Der Grund: Dieser Zufallszahlengenerator speichert intern einen "Startwert" (Seed). Wenn dieser Startwert in das fertige Programm eingebrannt wuerde, wuerde **jeder Start** die gleiche Folge von "Zufalls"-Zahlen erzeugen. Fuer eine Banking-Anwendung waere das eine Sicherheitskatastrophe -- die Verschluesselung waere vorhersagbar.

**Analogie:** Es waere so, als wuerde man den Wuerfel vor dem Spiel immer auf die gleiche Seite drehen. Egal wie oft man spielt -- die Ergebnisse waeren immer identisch. Genau das wuerde ein eingefrorener Zufallszahlen-Generator bewirken.

**Die Loesung:** Wir weisen GraalVM an: "Alle IAIK-Klassen sollen beim Uebersetzen initialisiert werden -- **ausser** die Zufallszahlen-Klassen. Die sollen erst beim Programmstart frisch initialisiert werden." So bekommt jeder Start seinen eigenen, frischen Zufallswert.

### 16.5 Problem 3: Der Postbote versteht die neue Adresse nicht

**Worum geht es?**

Unser Programm muss EBICS-XML-Nachrichten gegen Schema-Dateien (XSD) validieren -- also pruefen, ob eine XML-Nachricht das richtige Format hat. Die Schema-Dateien sind im Programm eingebettet, und das XML-Validierungssystem muss sie finden und laden.

**Was ging schief?**

In einer normalen JVM liefert Java beim Suchen nach eingebetteten Dateien eine "Adresse" wie `jar:file:/app/app.jar!/schemas/ebics.xsd`. Das XML-System (Xerces) versteht diese Adresse und kann die Datei laden.

Im Native Image gibt es aber keine JAR-Datei mehr -- die Dateien sind direkt im Programm eingebettet. Die Adresse sieht dann so aus: `resource:/schemas/ebics.xsd`. Und genau hier scheitert das XML-System: Es kennt den Adress-Typ `resource:` nicht und kann die Datei nicht laden.

Das Ergebnis: Keine Schema-Validierung moeglich, die EBICS-Nachrichten koennen nicht geprueft werden, die Banking-Funktion bricht mit einem Fehler ab.

**Analogie:** Man stelle sich einen Postboten vor, der Pakete nur an Adressen mit "Strasse + Hausnummer" liefert. Nun zieht man in ein neues Gebaeude um, das statt einer Strassenadresse nur einen GPS-Code als Adresse hat: "GPS:48.1234,11.5678". Der Postboten-Dienst (Xerces) versteht GPS-Codes nicht -- er kann das Paket nicht zustellen, obwohl das Gebaeude existiert.

**Die Loesung:** Wir haben eine eigene "Poststation" (`NativeSchemaFactory`) gebaut, die zwischen dem alten und dem neuen Adresssystem uebersetzt. Wenn das XML-System nach einer Datei fragt und eine `resource:`-Adresse bekommt, faengt unsere Poststation die Anfrage ab, liest die Datei selbst (ueber einen anderen Weg, der das `resource:`-Format versteht) und reicht den Inhalt an das XML-System weiter. Das XML-System merkt keinen Unterschied -- es bekommt die gewuenschte Datei, nur eben ueber einen Umweg.

### 16.6 Das Ergebnis

Nachdem alle drei Probleme geloest waren, funktioniert die gesamte Banking-Funktion im Native Image genau wie bei einer normalen JVM:
- Schluesseldateien werden entschluesselt (PBES2-Kryptographie)
- Die sichere Verbindung zum Banking-Server wird aufgebaut (TLS)
- Schluessel werden ausgetauscht (HPB-Protokoll)
- Eine SEPA-Ueberweisung wird hochgeladen und bestaetigt

Das fertige Programm ist ca. 122 MB gross, startet in wenigen Millisekunden statt mehreren Sekunden, und verbraucht deutlich weniger Speicher als die JVM-Variante. Ob es auch einen Vorteil beim Durchsatz und bei den Antwortzeiten bringt, wird der Benchmark zeigen.
