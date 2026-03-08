# ── Stage 1: CDS Training Run ────────────────────────────────────────────────
# Startet die Spring-Boot-App kurz, um eine dynamische CDS-Klassenliste zu erzeugen.
# Der /json-Endpoint braucht keine externe DB — die App kann im Build booten.
FROM eclipse-temurin:25-jre AS training

WORKDIR /app
COPY target/jvm-optim-demo-0.0.1-SNAPSHOT.jar app.jar

# App starten und nach 30 Sekunden beenden.
# -XX:ArchiveClassesAtExit erzeugt beim Shutdown automatisch das CDS-Archiv.
RUN java -XX:ArchiveClassesAtExit=app-cds.jsa \
        -Dspring.main.banner-mode=off \
        -jar app.jar &\
    APP_PID=$! && \
    sleep 30 && \
    kill $APP_PID && \
    wait $APP_PID || true

# ── Stage 2: Finales Runtime-Image ──────────────────────────────────────────
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=training /app/app.jar app.jar
COPY --from=training /app/app-cds.jsa app-cds.jsa

EXPOSE 8080

# SharedArchiveFile zeigt auf das im Training erzeugte CDS-Archiv.
# Zusaetzliche JVM-Flags kommen ueber JAVA_TOOL_OPTIONS vom Benchmark.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app-cds.jsa", "-jar", "app.jar"]
