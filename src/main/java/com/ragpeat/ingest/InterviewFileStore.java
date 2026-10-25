package com.ragpeat.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.ragpeat.ingest.Interview.Section;
import com.ragpeat.ingest.Interview.Turn;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lee nuestras transcripciones de entrevistas (data/transcripts/*.json), generadas por
 * scripts/transcribe_interviews.py a partir de los audios públicos: texto literal de Whisper, con
 * quién habla en cada turno (decidido por Claude) y el segundo en que empieza.
 *
 * Formato de cada archivo:
 *   {"id": "kmud-081201-cholesterol...", "show": "Ask the Herb Doctor (KMUD)", "date": "2008-12-01",
 *    "topic": "Cholesterol is an important molecule", "audio_url": "https://...mp3",
 *    "turns": [{"speaker": "HOST", "start": 0.0, "text": "..."}, {"speaker": "PEAT", ...}]}
 */
@Component
public class InterviewFileStore {

    private static final Path FOLDER = Path.of("data", "transcripts");

    /** PEAT / HOST / CALLER -> el nombre que verá Claude delante de cada turno. */
    private static final Map<String, String> SPEAKERS = Map.of("PEAT", Interview.PEAT, "HOST", "Host", "CALLER", "Caller");

    /**
     * Reemisiones: el mismo programa publicado dos veces con otro título. Detectadas comparando el texto de
     * Peat entre episodios (comparten el 94-100 % de las frases); nos quedamos con la primera emisión.
     */
    private static final Set<String> SKIPPED = Set.of(
            "kmud-110521-misconceptions-relating-to-serotonin-and-melatonin",   // = kmud-110415-fukujima-and-serotonin
            "kmud-130420-hormone-replacement-therapy");                         // = kmud-110218-inflammation-2

    /** Lo que guarda el script por episodio (los campos que no usamos se ignoran). */
    record Episode(String id, String show, String date, String topic, String audio_url, List<EpisodeTurn> turns) {}

    record EpisodeTurn(String speaker, double start, String text) {}

    private final JsonMapper json;

    public InterviewFileStore(JsonMapper json) {
        this.json = json;
    }

    /** Vacía si aún no hay transcripciones (p. ej. en el servidor, donde todavía no se usan). */
    public List<Interview> loadAll() throws IOException {
        List<Interview> interviews = new ArrayList<>();
        if (!Files.isDirectory(FOLDER)) {
            return interviews;
        }
        try (Stream<Path> files = Files.list(FOLDER)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                Episode episode = json.readValue(file.toFile(), Episode.class);
                if (!SKIPPED.contains(episode.id())) {
                    interviews.add(toInterview(episode));
                }
            }
        }
        return interviews;
    }

    static Interview toInterview(Episode episode) {
        List<Turn> turns = episode.turns().stream()
                .map(t -> new Turn(SPEAKERS.getOrDefault(t.speaker(), t.speaker()), t.text().strip()))
                .toList();
        // Una sola sección por episodio: la transcripción automática no viene dividida por temas.
        return new Interview("interview-" + episode.id(), title(episode), episode.audio_url(),
                List.of(new Section("", turns)));
    }

    /** "Ask the Herb Doctor (KMUD): Cholesterol is an important molecule (2008)". */
    static String title(Episode episode) {
        String year = episode.date() == null ? "" : " (" + episode.date().substring(0, 4) + ")";
        return episode.show() + ": " + episode.topic() + year;
    }
}
