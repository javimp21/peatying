package com.ragpeat.chat;

/** Un mensaje anterior de la conversación: quién lo dijo ("user" o "assistant") y qué dijo. */
public record Turn(String role, String content) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
}
