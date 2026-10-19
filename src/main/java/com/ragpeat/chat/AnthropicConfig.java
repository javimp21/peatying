package com.ragpeat.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Crea el cliente de Claude una sola vez para toda la aplicación.
 * @Bean: el objeto que devuelve este método lo gestiona Spring y lo inyecta donde se pida.
 */
@Configuration
public class AnthropicConfig {

    @Bean
    AnthropicClient anthropicClient(@Value("${anthropic.api-key}") String apiKey) {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }
}
