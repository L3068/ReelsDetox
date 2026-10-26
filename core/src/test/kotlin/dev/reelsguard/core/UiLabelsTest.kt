package dev.reelsguard.core

import org.junit.Assert.*
import org.junit.Test

class UiLabelsTest {
    @Test fun knownActionLabelsSupportSevenInstagramLanguages() {
        for (like in listOf("Like", "Kedvelés", "Gefällt mir", "J’aime", "Me gusta", "Mi piace", "Curtir")) {
            assertEquals(like, UiLabel.LIKE, UiLabels.parse(like))
        }
        for (share in listOf("Share", "Megosztás", "Teilen", "Partager", "Compartir", "Condividi", "Compartilhar", "Partilhar")) {
            assertEquals(share, UiLabel.SHARE, UiLabels.parse(share))
        }
    }
    @Test fun arbitraryContentAndCountersCannotBecomeControlLabels() {
        for (text in listOf("I like reels", "Reels from someone", "Like 100", "private comment", "Comment\nmessage", "")) {
            assertNull(text, UiLabels.parse(text))
        }
        assertNull(UiLabels.parse(null))
        assertEquals(UiLabel.LIKE, UiLabels.parse("  GEFÄLLT MIR  "))
    }
}
