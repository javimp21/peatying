package com.ragpeat.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Cómo se guardan las citas de una respuesta (columna cited_chunks). */
class ConversationRepositoryTest {

    @Test
    void theCitedChunksSurviveBeingStoredAsText() {
        String stored = ConversationRepository.encodeCited(Map.of(3, 8811L, 1, 15342L));

        assertThat(stored).isEqualTo("1:15342,3:8811");
        assertThat(ConversationRepository.decodeCited(stored)).containsExactly(Map.entry(1, 15342L), Map.entry(3, 8811L));
    }

    @Test
    void anEmptyOrBrokenValueGivesNoCitations() {
        assertThat(ConversationRepository.decodeCited(null)).isEmpty();
        assertThat(ConversationRepository.decodeCited("")).isEmpty();
        assertThat(ConversationRepository.decodeCited("1:abc,x,2:7")).containsExactly(Map.entry(2, 7L));
    }
}
