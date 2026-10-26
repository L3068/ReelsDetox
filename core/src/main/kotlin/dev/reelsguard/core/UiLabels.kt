package dev.reelsguard.core

import java.util.Locale

/** Closed vocabulary only: never interpret arbitrary post/message text as a control. */
object UiLabels {
    private fun labels(label: UiLabel, vararg values: String) = values.map { it to label }
    private val known = (
        labels(UiLabel.REELS, "reels", "reels-videók") +
        labels(UiLabel.LIKE, "like", "kedvelés", "gefällt mir", "j'aime", "me gusta", "mi piace", "curtir", "gosto") +
        labels(UiLabel.COMMENT, "comment", "hozzászólás", "kommentieren", "kommentare", "commenter", "commentaires", "comentar", "commenta", "commenti") +
        labels(UiLabel.SHARE, "share", "megosztás", "teilen", "partager", "compartir", "condividi", "compartilhar", "partilhar") +
        labels(UiLabel.HOME, "home", "kezdőlap", "startseite", "accueil", "inicio", "início") +
        labels(UiLabel.STORIES, "stories", "történetek", "historias", "storie") +
        labels(UiLabel.DIRECT, "direct", "messages", "üzenetek", "nachrichten", "mensajes", "messaggi", "mensagens")
    ).toMap()

    fun parse(description: CharSequence?): UiLabel? = description?.toString()?.trim()
        ?.lowercase(Locale.ROOT)?.replace('\u2019', '\'')?.let(known::get)
}
