package com.ragpeat.chat;

/**
 * Avisos que ChatService va dando MIENTRAS responde, para quien quiera enterarse (la web, con streaming).
 * Los métodos tienen una implementación vacía por defecto: cada uno implementa solo los que le interesan.
 */
public interface ChatListener {

    /** Claude ha pedido una búsqueda. */
    default void onSearch(String query) {}

    /** Ha llegado un trozo nuevo de texto de la respuesta. */
    default void onText(String delta) {}

    /**
     * El texto enviado en este turno no era la respuesta final, porque Claude ha decidido buscar
     * (a veces escribe algo como "Voy a buscar..." antes de pedir la búsqueda). Hay que descartarlo.
     */
    default void onDiscardText() {}

    /** Para quien no necesita avisos (la terminal, el banco de pruebas). */
    ChatListener NONE = new ChatListener() {};
}
