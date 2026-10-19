package com.ragpeat.embedding;

import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Habla con la API de Voyage: le mandas textos y te devuelve un embedding (vector) por cada uno.
 * Documentación: https://docs.voyageai.com/reference/embeddings-api
 */
@Component
public class VoyageClient {

    private static final Logger log = LoggerFactory.getLogger(VoyageClient.class);
    private static final String MODEL = "voyage-4";
    public static final int DIMENSIONS = 1024; // tiene que coincidir con vector(1024) de la migración V2

    private final RestClient http;

    // @Value lee la propiedad voyage.api-key de application.properties (que a su vez sale de la variable de entorno).
    public VoyageClient(RestClient.Builder builder, @Value("${voyage.api-key}") String apiKey) {
        this.http = builder
                .baseUrl("https://api.voyageai.com/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /** Para los chunks que guardamos. */
    public List<float[]> embedDocuments(List<String> texts) {
        return embed(texts, "document");
    }

    /** Para la pregunta del usuario. */
    public float[] embedQuery(String query) {
        return embed(List.of(query), "query").getFirst();
    }

    /**
     * input_type ("document" o "query") le dice a Voyage para qué es el texto: así optimiza
     * los vectores para que una PREGUNTA quede cerca de los DOCUMENTOS que la responden.
     */
    private List<float[]> embed(List<String> texts, String inputType) {
        EmbeddingRequest request = new EmbeddingRequest(texts, MODEL, inputType);

        for (int attempt = 1; ; attempt++) {
            try {
                EmbeddingResponse response = http.post()
                        .uri("/embeddings")
                        .body(request)                       // el record se convierte a JSON solo (Jackson)
                        .retrieve()                          // lanza excepción si la respuesta es un error 4xx/5xx
                        .body(EmbeddingResponse.class);      // y el JSON de vuelta se convierte en el record

                // La API devuelve cada vector con su "index"; ordenamos por él para asegurar
                // que el vector i corresponde al texto i.
                return response.data().stream()
                        .sorted(Comparator.comparingInt(EmbeddingItem::index))
                        .map(EmbeddingItem::embedding)
                        .toList();
            } catch (HttpClientErrorException.TooManyRequests e) {
                // 429 = "vas demasiado rápido". Esperamos y reintentamos, cada vez un poco más.
                if (attempt == 5) {
                    throw e;
                }
                long waitSeconds = 20L * attempt;
                log.warn("Límite de Voyage alcanzado; espero {} s (intento {}/5)", waitSeconds, attempt);
                sleep(waitSeconds * 1000);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // Estos records reflejan el JSON que manda y devuelve la API.
    // @JsonProperty: en JSON el campo se llama "input_type", pero en Java preferimos inputType.
    record EmbeddingRequest(List<String> input, String model, @JsonProperty("input_type") String inputType) {}

    record EmbeddingResponse(List<EmbeddingItem> data) {}

    record EmbeddingItem(int index, float[] embedding) {}
}
