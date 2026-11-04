package com.ragpeat.chat;

import java.time.Duration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Crea el cliente de Claude una sola vez para toda la aplicación.
 * @Bean: el objeto que devuelve este método lo gestiona Spring y lo inyecta donde se pida.
 */
@Configuration
public class AnthropicConfig {

    /**
     * Para las llamadas que hace la web mientras alguien espera (chat, documentos, traducciones, idioma). Por defecto
     * el SDK espera hasta 10 minutos por llamada: si Claude se atasca, cada petición ocuparía un hueco todo ese tiempo.
     * 2 minutos sobran (leer un PDF tarda menos de uno). Los comandos de terminal (juez, temas) piden respuestas mucho
     * más largas y se quedan con el valor por defecto.
     */
    public static final RequestOptions WEB_REQUEST = RequestOptions.builder()
            .timeout(Timeout.builder().connect(Duration.ofSeconds(10)).request(Duration.ofMinutes(2)).build())
            .build();

    @Bean
    AnthropicClient anthropicClient(@Value("${anthropic.api-key}") String apiKey) {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }
}
