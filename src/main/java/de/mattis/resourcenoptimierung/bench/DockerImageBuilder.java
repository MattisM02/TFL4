package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Baut Docker-Images automatisch, bevor ein Benchmark-Run startet.
 *
 * Funktionsweise:
 * <ul>
 *   <li>Sammelt die eindeutigen Docker-Image-Tags aus dem BenchmarkPlan</li>
 *   <li>Prueft per {@code docker image inspect}, ob jedes Image lokal vorhanden ist</li>
 *   <li>Baut fehlende (oder bei {@code forceRebuild} alle) Images automatisch</li>
 *   <li>Baut bei Bedarf vorher die Maven-JAR ({@code mvnw package -DskipTests})</li>
 * </ul>
 *
 * Die Zuordnung Image-Tag → Dockerfile ist fest verdrahtet und muss bei
 * neuen Images erweitert werden.
 */
public class DockerImageBuilder {

    /**
     * Mapping von Docker-Image-Tag auf das zugehoerige Dockerfile.
     * Reihenfolge: erst non-EK, dann EK-Varianten.
     */
    static final Map<String, String> IMAGE_DOCKERFILE_MAP = Map.of(
            "tfl4-ek-bench:jvm",              "Dockerfile",
            "tfl4-ek-bench:jvm-ek",           "Dockerfile.with-ek",
            "tfl4-ek-bench:openj9",           "Dockerfile.openj9",
            "tfl4-ek-bench:openj9-ek",        "Dockerfile.openj9.with-ek",
            "tfl4-ek-bench:native",           "Dockerfile.native",
            "tfl4-ek-bench:native-ek",        "Dockerfile.native.with-ek",
            "tfl4-ek-bench:graalvm-jit",      "Dockerfile.graalvm-jit",
            "tfl4-ek-bench:graalvm-jit-ek",   "Dockerfile.graalvm-jit.with-ek",
            "tfl4-ek-bench:jvm-cds",          "Dockerfile.cds",
            "tfl4-ek-bench:jvm-cds-ek",       "Dockerfile.cds.with-ek"
    );

    /**
     * Pfad zur gebauten JAR-Datei, die in die Docker-Images kopiert wird.
     */
    static final String JAR_PATH = "target/jvm-optim-demo-0.0.1-SNAPSHOT.jar";

    /** Timeout fuer {@code docker image inspect} (schnelle Operation). */
    private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(10);

    /** Timeout fuer {@code docker build} (kann bei Native Images mehrere Minuten dauern). */
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);

    /** Timeout fuer {@code mvnw package} (inkl. Kompilierung). */
    private static final Duration MAVEN_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Stellt sicher, dass alle vom Plan benoetigten Docker-Images lokal vorhanden sind.
     *
     * <p>Bei {@code forceRebuild=true} werden alle Images neu gebaut (inkl. Maven-Package).
     * Bei {@code forceRebuild=false} werden nur fehlende Images gebaut.
     * Wenn mindestens ein Image gebaut werden muss, wird vorher geprueft, ob die
     * JAR-Datei vorhanden ist, und bei Bedarf {@code mvnw package -DskipTests} ausgefuehrt.</p>
     *
     * @param plan          der Benchmark-Plan mit den zu pruefenden Konfigurationen
     * @param forceRebuild  wenn true, werden alle Images neu gebaut (auch vorhandene)
     * @throws RuntimeException wenn ein Build fehlschlaegt
     */
    public static void ensureImagesExist(BenchmarkPlan plan, boolean forceRebuild) {
        Set<String> uniqueTags = collectUniqueImageTags(plan);

        if (uniqueTags.isEmpty()) {
            return;
        }

        // Bestimme, welche Images gebaut werden muessen
        Set<String> toBuild = new LinkedHashSet<>();
        for (String tag : uniqueTags) {
            if (forceRebuild || !imageExists(tag)) {
                toBuild.add(tag);
            }
        }

        if (toBuild.isEmpty()) {
            System.out.println("All Docker images already present.");
            return;
        }

        // Vor dem ersten Build: JAR sicherstellen
        packageIfNeeded(forceRebuild);

        // Images bauen
        int built = 0;
        for (String tag : toBuild) {
            String dockerfile = IMAGE_DOCKERFILE_MAP.get(tag);
            if (dockerfile == null) {
                System.out.println("[WARN] Unknown image tag '" + tag + "' — skipping auto-build (Docker will try to pull).");
                continue;
            }

            if (!Files.exists(Path.of(dockerfile))) {
                throw new RuntimeException("Dockerfile not found: " + dockerfile + " (for image " + tag + ")");
            }

            buildImage(tag, dockerfile);
            built++;
        }

        System.out.println("Docker images ready (" + built + " built, "
                + (uniqueTags.size() - built) + " cached).");
    }

    /**
     * Sammelt alle eindeutigen Docker-Image-Tags aus dem Plan.
     * Reihenfolge: erste Vorkommen im Plan.
     *
     * @param plan Benchmark-Plan
     * @return Set der eindeutigen Image-Tags (insertion order)
     */
    static Set<String> collectUniqueImageTags(BenchmarkPlan plan) {
        Set<String> tags = new LinkedHashSet<>();
        for (BenchmarkConfig cfg : plan.configs) {
            tags.add(cfg.dockerImage());
        }
        return tags;
    }

    /**
     * Prueft ob ein Docker-Image lokal vorhanden ist.
     *
     * @param tag Image-Tag (z.B. "tfl4-ek-bench:jvm")
     * @return true wenn das Image lokal existiert
     */
    static boolean imageExists(String tag) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", tag);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain output to avoid pipe deadlock
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) { /* discard */ }
            }
            boolean finished = p.waitFor(INSPECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Baut ein Docker-Image.
     * Stdout/stderr werden auf die Konsole weitergeleitet, damit der Benutzer
     * den Build-Fortschritt sieht (besonders wichtig bei Native Image Builds).
     *
     * @param tag        Image-Tag (z.B. "tfl4-ek-bench:jvm")
     * @param dockerfile Pfad zum Dockerfile relativ zum Projektverzeichnis
     * @throws RuntimeException wenn der Build fehlschlaegt
     */
    static void buildImage(String tag, String dockerfile) {
        System.out.println();
        System.out.println(">>> Building Docker image: " + tag + " (from " + dockerfile + ")");

        if (tag.contains("native")) {
            System.out.println("    (Native Image build — this may take several minutes)");
        }

        try {
            List<String> cmd = List.of("docker", "build", "-t", tag, "-f", dockerfile, ".");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();  // Stdout/stderr direkt an Konsole weiterleiten
            Process p = pb.start();

            boolean finished = p.waitFor(BUILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException("Docker build timed out after " + BUILD_TIMEOUT.toMinutes()
                        + " minutes for image: " + tag);
            }

            if (p.exitValue() != 0) {
                throw new RuntimeException("Docker build failed (exit code " + p.exitValue()
                        + ") for image: " + tag + " (Dockerfile: " + dockerfile + ")");
            }

            System.out.println("    OK: " + tag);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Docker build failed for " + tag + ": " + e.getMessage(), e);
        }
    }

    /**
     * Stellt sicher, dass die JAR-Datei vorhanden ist.
     * Bei {@code forceRebuild=true} wird Maven immer ausgefuehrt.
     * Sonst nur, wenn die JAR fehlt.
     *
     * @param forceRebuild true = Maven immer ausfuehren
     * @throws RuntimeException wenn Maven fehlschlaegt
     */
    static void packageIfNeeded(boolean forceRebuild) {
        if (!forceRebuild && Files.exists(Path.of(JAR_PATH))) {
            return;
        }

        System.out.println();
        if (forceRebuild) {
            System.out.println(">>> Rebuilding Maven artifact (--rebuild)");
        } else {
            System.out.println(">>> JAR not found (" + JAR_PATH + "), running Maven package...");
        }

        try {
            String mvnCmd = resolveMavenCommand();
            List<String> cmd = List.of(mvnCmd, "package", "-DskipTests", "-q");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process p = pb.start();

            boolean finished = p.waitFor(MAVEN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException("Maven package timed out after " + MAVEN_TIMEOUT.toMinutes() + " minutes");
            }

            if (p.exitValue() != 0) {
                throw new RuntimeException("Maven package failed (exit code " + p.exitValue()
                        + "). Fix compilation errors and retry.");
            }

            if (!Files.exists(Path.of(JAR_PATH))) {
                throw new RuntimeException("Maven package succeeded but JAR not found at " + JAR_PATH);
            }

            System.out.println("    OK: Maven package complete");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Maven package failed: " + e.getMessage(), e);
        }
    }

    /**
     * Bestimmt den Maven-Wrapper-Befehl abhaengig vom Betriebssystem.
     *
     * @return "mvnw.cmd" auf Windows, "./mvnw" auf Linux/Mac
     */
    static String resolveMavenCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "mvnw.cmd";
        }
        return "./mvnw";
    }

    // Privater Konstruktor — reine Utility-Klasse
    private DockerImageBuilder() {}
}
