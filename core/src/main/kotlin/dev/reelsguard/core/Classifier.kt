package dev.reelsguard.core

/** Candidate adapter with limited Pixel 8 / Android 17 / Instagram 452.1 lower-tab evidence.
 * Instagram publishes no stable IDs; this is not a universal compatibility claim.
 * Each candidate needs device evidence before it can be called supported. Keeping this table
 * explicit makes replacement possible without changing blocking or accounting policy.
 * Instagram calls Stories "reel" internally; reel_viewer is deliberately an OTHER marker.
 */
object InstagramCandidates {
    const val PACKAGE_NAME = "com.instagram.android"
    val viewerIds: Set<String> = setOf(
        "$PACKAGE_NAME:id/clips_viewer_view_pager", "$PACKAGE_NAME:id/clips_viewer_container")
    val actionIds: Set<String> = setOf(
        "$PACKAGE_NAME:id/clips_like_button", "$PACKAGE_NAME:id/clips_comment_button",
        "$PACKAGE_NAME:id/clips_share_button", "$PACKAGE_NAME:id/comment_button", "$PACKAGE_NAME:id/direct_share_button")
    val otherScreenIds: Set<String> = setOf(
        "$PACKAGE_NAME:id/reel_viewer", "$PACKAGE_NAME:id/direct_thread_message_list",
        "$PACKAGE_NAME:id/feed_timeline", "$PACKAGE_NAME:id/profile_grid")
    val navigationIds: Set<String> = setOf("$PACKAGE_NAME:id/clips_tab")
    val otherNavigationIds = setOf("$PACKAGE_NAME:id/feed_tab", "$PACKAGE_NAME:id/direct_tab",
        "$PACKAGE_NAME:id/search_tab", "$PACKAGE_NAME:id/profile_tab")
    val allIds: Set<String> = viewerIds + actionIds + otherScreenIds + navigationIds + otherNavigationIds
}

fun interface ReelsClassifier {
    fun classify(snapshot: ScreenSnapshot): Classification
}

class CandidateInstagramClassifier : ReelsClassifier {
    override fun classify(snapshot: ScreenSnapshot): Classification {
        if (snapshot.packageName == null) return Classification.UNKNOWN
        if (snapshot.packageName != InstagramCandidates.PACKAGE_NAME) return Classification.OTHER
        if (!snapshot.complete || !snapshot.appBounds.valid) return Classification.UNKNOWN
        val nodes = snapshot.nodes.filter { it.visible }
        val viewers = nodes.filter { it.viewId in InstagramCandidates.viewerIds }
        val other = nodes.any { it.viewId in InstagramCandidates.otherScreenIds &&
            snapshot.appBounds.contains(it.bounds) }
        if (viewers.isEmpty()) return if (other || nodes.any { it.selected && it.viewId in InstagramCandidates.otherNavigationIds })
            Classification.OTHER else Classification.UNKNOWN
        // A stale mixed window/tree is not evidence that it is safe to navigate Back.
        if (other) return Classification.UNKNOWN
        val verified = viewers.any { viewer ->
            snapshot.appBounds.contains(viewer.bounds) &&
                viewer.bounds.width >= snapshot.appBounds.width / 2 &&
                viewer.bounds.height >= snapshot.appBounds.height / 2 &&
                (nodes.any { it.selected && it.viewId in InstagramCandidates.navigationIds } || nodes.filter { node ->
                    node.clickable && viewer.viewId in node.ancestorViewIds && viewer.bounds.contains(node.bounds)
                }.mapNotNull { node ->
                    when (node.viewId) {
                        "com.instagram.android:id/clips_like_button" -> UiLabel.LIKE
                        "com.instagram.android:id/clips_comment_button", "com.instagram.android:id/comment_button" -> UiLabel.COMMENT
                        "com.instagram.android:id/clips_share_button", "com.instagram.android:id/direct_share_button" -> UiLabel.SHARE
                        else -> node.label?.takeIf { it == UiLabel.LIKE || it == UiLabel.COMMENT || it == UiLabel.SHARE }
                    }
                }.distinct().size >= 2)
        }
        return if (verified) Classification.REELS else Classification.UNKNOWN
    }
}
