package com.ragpeat.chat;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Para probar el chat desde la terminal:
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar ask "¿Es bueno comer manzanas?"
 */
@Component
public class AskCommand implements CommandLineRunner {

    // Precios de Claude Haiku 5.5 en dólares por millón de tokens (octubre de 2026), solo para mostrar el coste.
    private static final double INPUT_PRICE = 0.10, OUTPUT_PRICE = 0.50;

    private final ChatService chatService;

    public AskCommand(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public void run(String... args) {
        if (args.length < 2 || !args[0].equals("ask")) {
            return;
        }
        ChatAnswer answer = chatService.answer(args[1]);

        System.out.println("\n=== Pregunta: " + args[1] + "\n");
        System.out.println(answer.text());
        System.out.println("\n--- Fuentes citadas");
        answer.citedSources().forEach((number, source) ->
                System.out.printf("[%d] %s  %s%n", number, source.title(), source.url()));
        double cost = (answer.inputTokens() * INPUT_PRICE + answer.outputTokens() * OUTPUT_PRICE) / 1_000_000;
        System.out.printf("%n--- %d búsquedas · %d tokens de entrada · %d de salida · ~$%.4f%n",
                answer.searches(), answer.inputTokens(), answer.outputTokens(), cost);
    }
}
