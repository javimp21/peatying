package com.ragpeat.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * "Claude a secas": el mismo modelo que usa el bot, pero SIN búsqueda ni artículos.
 * Responde solo con lo que sabe. Es contra lo que comparamos el RAG.
 * Para que la comparación sea justa, su prompt también le pide no inventar y no salirse del tema.
 */
@Component
public class BaselineChat {

    private final AnthropicClient claude;
    private final String model;
    private final String systemPrompt;

    public BaselineChat(AnthropicClient claude, @Value("${anthropic.model}") String model,
                        @Value("classpath:prompts/baseline.md") Resource prompt) throws IOException {
        this.claude = claude;
        this.model = model;
        this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }

    /** Tokens gastados en esta ejecución. */
    long inputTokens, outputTokens;

    public String answer(String question) {
        var response = claude.messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(systemPrompt)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .addUserMessage(question)
                .build());
        inputTokens += response.usage().inputTokens();
        outputTokens += response.usage().outputTokens();

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }
}
