package com.ragpeat.topics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.ragpeat.search.SearchResult;
import com.ragpeat.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Genera las páginas de temas (peatying.com/temas/...): una página fija por tema, para que Google y los
 * asistentes de IA tengan texto que leer (las respuestas del chat no son páginas).
 *
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar topics                  todas (las que ya existen se rehacen)
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar topics azucar gelatina  solo esas
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar topics render           solo vuelve a pintar el HTML, sin llamar a Claude
 *
 * Para cada tema (topics/topics.json): varias búsquedas como las del chat, y Claude redacta la página SOLO con
 * esos fragmentos, citándolos. El contenido se guarda en topics/generated/<slug>.json (va a git: así se puede
 * revisar y repintar sin volver a pagar) y el HTML en static/temas/.
 */
@Component
public class TopicsCommand implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TopicsCommand.class);
    private static final Path TOPICS = Path.of("topics", "topics.json");
    private static final Path GENERATED = Path.of("topics", "generated");
    private static final int RESULTS_PER_SEARCH = 6;
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private static final String SYSTEM = """
            Escribes una página web en español de España, clara y divulgativa, sobre lo que pensaba Ray Peat (1936-2022,
            biólogo del enfoque «bioenergético») acerca de un tema. Tu ÚNICA fuente son los fragmentos numerados que se
            te dan: artículos escritos por Peat o transcripciones automáticas de entrevistas de radio.

            Reglas:
            - Cada afirmación sobre lo que pensaba Peat lleva al final su cita con el número del fragmento: [3]. Varias: [3][7].
              Usa solo números que existan. No uses otro formato de cita.
            - Nada que no esté en los fragmentos. Si los fragmentos no cubren algo, no lo menciones.
            - En las entrevistas, solo cuenta lo que dice Ray Peat; lo que dicen el presentador o los oyentes no es su opinión.
            - Explica con tus palabras: sin citas literales y sin comillas para el texto de Peat. Términos técnicos en español
              (por ejemplo «ácidos grasos poliinsaturados (PUFA)»).
            - Atribuye siempre: «según Peat», «para él», «sostenía que»... Nunca presentes sus ideas como hechos probados.
            - Sin consejos médicos personales y sin avisos tipo «consulta a tu médico» (la página ya lleva un aviso).
            - Texto plano: sin Markdown, sin negritas, sin listas dentro de los párrafos.
            - Cada título de sección nombra solo lo que cuenta esa sección (nada de temas que luego no aparecen).

            Responde SOLO con un objeto JSON, sin nada antes ni después, con esta forma:
            {
              "description": "resumen para Google, 140-155 caracteres",
              "intro": "2 o 3 frases con la idea central de Peat sobre el tema, con citas",
              "sections": [{"heading": "título corto, máximo 8 palabras", "paragraphs": ["2 a 4 frases con citas"]}],
              "faq": [{"q": "pregunta que alguien buscaría en Google", "a": "respuesta de 2 o 3 frases con citas"}]
            }
            Entre 4 y 6 secciones (1 o 2 párrafos cada una) y entre 3 y 4 preguntas.
            """;

    record Topic(String slug, String name, String title, String question, List<List<String>> searches) {}

    record Section(String heading, List<String> paragraphs) {}

    record Faq(String q, String a) {}

    record Source(String title, String url, String type) {}

    /** Lo que se guarda por tema: lo que escribió Claude y las fuentes ya renumeradas en orden de aparición. */
    record Page(String slug, String name, String title, String question, String description, String intro,
                List<Section> sections, List<Faq> faq, List<Source> sources) {}

    private final SearchService search;
    private final AnthropicClient client;
    private final JsonMapper json;
    private final TopicPageRenderer renderer;
    private final String model;

    public TopicsCommand(SearchService search, AnthropicClient client, JsonMapper json, TopicPageRenderer renderer,
                         @Value("${topics.model:claude-sonnet-5-5}") String model) {
        this.search = search;
        this.client = client;
        this.json = json;
        this.renderer = renderer;
        this.model = model;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0 || !args[0].equals("topics")) {
            return;
        }
        List<Topic> topics = json.readValue(TOPICS.toFile(), new TypeReference<List<Topic>>() {});
        List<String> wanted = List.of(args).subList(1, args.length);
        if (!wanted.equals(List.of("render"))) {
            Files.createDirectories(GENERATED);
            for (Topic topic : topics) {
                if (wanted.isEmpty() || wanted.contains(topic.slug())) {
                    Page page = generate(topic);
                    json.writerWithDefaultPrettyPrinter().writeValue(GENERATED.resolve(topic.slug() + ".json").toFile(), page);
                    log.info("Tema {}: {} secciones, {} preguntas, {} fuentes",
                            topic.slug(), page.sections().size(), page.faq().size(), page.sources().size());
                }
            }
        }
        // Se pintan todas las que haya generadas, en el orden de topics.json (también el índice y el sitemap).
        List<Page> pages = new ArrayList<>();
        for (Topic topic : topics) {
            Path file = GENERATED.resolve(topic.slug() + ".json");
            if (Files.exists(file)) {
                pages.add(json.readValue(file.toFile(), Page.class));
            }
        }
        renderer.writeAll(pages);
        log.info("Pintadas {} páginas de temas", pages.size());
    }

    private Page generate(Topic topic) {
        // 1. Las búsquedas, sin repetir fragmentos. Su posición + 1 es el número [n] que ve Claude.
        Map<Long, SearchResult> found = new LinkedHashMap<>();
        for (List<String> s : topic.searches()) {
            for (SearchResult r : search.search(s.get(0), s.get(1), RESULTS_PER_SEARCH)) {
                found.putIfAbsent(r.chunkId(), r);
            }
        }
        List<SearchResult> fragments = new ArrayList<>(found.values());
        StringBuilder prompt = new StringBuilder("Tema: lo que pensaba Ray Peat sobre " + topic.name() + ".\n\nFragmentos:\n\n");
        for (int i = 0; i < fragments.size(); i++) {
            SearchResult r = fragments.get(i);
            prompt.append("[").append(i + 1).append("] ")
                    .append(r.isInterview() ? "ENTREVISTA: " : r.isBook() ? "LIBRO: " : "ARTÍCULO: ").append(r.title()).append("\n")
                    .append(r.content()).append("\n\n");
        }

        // 2. Claude redacta. Si cita un número que no existe, se repite (una vez).
        for (int attempt = 1; ; attempt++) {
            Message response = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(8000L)
                    .system(SYSTEM)
                    .addUserMessage(prompt.toString())
                    .build());
            String text = response.content().stream().flatMap(b -> b.text().stream()).map(TextBlock::text)
                    .reduce("", String::concat);
            Draft draft = json.readValue(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1), Draft.class);
            TreeSet<Integer> bad = new TreeSet<>();
            for (String part : draft.allText()) {
                Matcher m = CITATION.matcher(part);
                while (m.find()) {
                    int n = Integer.parseInt(m.group(1));
                    if (n < 1 || n > fragments.size()) bad.add(n);
                }
            }
            if (bad.isEmpty()) {
                return renumber(topic, draft, fragments);
            }
            if (attempt == 2) {
                throw new IllegalStateException(topic.slug() + ": cita fragmentos que no existen " + bad);
            }
            log.warn("{}: citas inexistentes {}, se repite", topic.slug(), bad);
        }
    }

    record Draft(String description, String intro, List<Section> sections, List<Faq> faq) {
        List<String> allText() {
            List<String> all = new ArrayList<>(List.of(intro));
            sections.forEach(s -> all.addAll(s.paragraphs()));
            faq.forEach(f -> all.add(f.a()));
            return all;
        }
    }

    /**
     * Las citas pasan a numerarse 1, 2, 3... en el orden en que aparecen en la página, y solo con las fuentes
     * citadas. Dos fragmentos del mismo artículo se convierten en la misma fuente.
     */
    static Page renumber(Topic topic, Draft draft, List<SearchResult> fragments) {
        Map<String, Integer> numberByUrl = new LinkedHashMap<>();
        List<Source> sources = new ArrayList<>();
        UnaryOperator<String> fix = text -> {
            Matcher m = CITATION.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                SearchResult r = fragments.get(Integer.parseInt(m.group(1)) - 1);
                int n = numberByUrl.computeIfAbsent(r.url(), url -> {
                    sources.add(new Source(r.title(), r.url(), r.sourceType()));
                    return sources.size();
                });
                m.appendReplacement(out, "[" + n + "]");
            }
            m.appendTail(out);
            // "[2][2]" (dos fragmentos del mismo artículo) -> "[2]"
            return out.toString().replaceAll("(\\[\\d+])\\1+", "$1");
        };
        String intro = fix.apply(draft.intro());
        List<Section> sections = draft.sections().stream()
                .map(s -> new Section(s.heading(), s.paragraphs().stream().map(fix).toList())).toList();
        List<Faq> faq = draft.faq().stream().map(f -> new Faq(f.q(), fix.apply(f.a()))).toList();
        return new Page(topic.slug(), topic.name(), topic.title(), topic.question(), draft.description(), intro,
                sections, faq, sources);
    }
}
