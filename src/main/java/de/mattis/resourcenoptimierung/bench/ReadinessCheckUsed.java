package de.mattis.resourcenoptimierung.bench;


/**
 * Beschreibt, welcher Mechanismus zur Ermittlung der Readiness eines
 * Containers während eines Benchmark-Runs verwendet wurde.
 *
 * <p>Die Readiness-Zeit ist die Dauer vom Start des Containers bis zu dem
 * Zeitpunkt, an dem der Service als „bereit“ gilt und Requests erfolgreich
 * beantworten kann.</p>
 *
 * <p>Da nicht jede Anwendung oder Konfiguration denselben Readiness-Endpoint
 * zuverlässig bereitstellt, existiert eine Fallback-Strategie.
 * Dieses Enum dokumentiert transparent, welcher Check letztlich erfolgreich war.</p>
 *
 * <p>Der verwendete Check wird im {@link RunResult} gespeichert und später
 * in der Konsole sowie in Exporten ausgegeben, um Messergebnisse korrekt
 * interpretieren zu können.</p>
 */
public enum ReadinessCheckUsed {

    /**
     * Readiness wurde über den dedizierten Spring-Boot-Endpunkt
     * {@code /actuator/health/readiness} ermittelt.
     *
     * <p>Dies ist der bevorzugte und semantisch korrekteste Mechanismus,
     * da er explizit zwischen Liveness und Readiness unterscheidet.</p>
     *
     * <p>Voraussetzungen:</p>
     * <ul>
     *   <li>Spring Boot Actuator ist eingebunden</li>
     *   <li>{@code management.endpoint.health.probes.enabled=true}</li>
     * </ul>
     */
    ACTUATOR_READINESS,

    /**
     * Fallback-Mechanismus: Readiness wurde über den allgemeinen
     * {@code /actuator/health}-Endpoint ermittelt.
     *
     * <p>Dieser Check wird verwendet, wenn der dedizierte Readiness-Endpoint
     * nicht verfügbar ist (z. B. ältere Spring-Boot-Versionen oder reduzierte Konfiguration).</p>
     *
     * <p>Nachteil: Es wird nicht explizit zwischen Liveness und Readiness unterschieden.</p>
     */
    ACTUATOR_HEALTH,

    /**
     * Letzter Fallback: Der Service gilt als „bereit“, sobald ein
     * HTTP-GET auf {@code /json} (oder den konfigurierten Workload-Endpunkt)
     * erstmals mit Status {@code 200 OK} beantwortet wird.
     *
     * <p>Dieser Mechanismus ist technisch robust, aber semantisch unsauber,
     * da er Readiness indirekt aus der Erreichbarkeit eines Business-Endpunkts ableitet.</p>
     *
     * <p>Er wird nur verwendet, wenn keine Actuator-Endpunkte verfügbar sind.</p>
     */
    WORKLOAD_UNTIL_200
}