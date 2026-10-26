package dev.reelsguard.core
import org.junit.Assert.*
import org.junit.Test
class SharedReelEpisodeTest {
 private val app=Bounds(0,0,1080,2400)
 private val viewerId="com.instagram.android:id/clips_viewer_view_pager"
 private val viewer=UiNode(viewerId,bounds=Bounds(0,132,1080,2176))
 private val actions=listOf(
  UiNode("com.instagram.android:id/comment_button",bounds=Bounds(943,1227,1059,1343),clickable=true,ancestorViewIds=setOf(viewerId)),
  UiNode("com.instagram.android:id/direct_share_button",bounds=Bounds(943,1593,1059,1709),clickable=true,ancestorViewIds=setOf(viewerId)))
 private fun screen(nodes:List<UiNode>)=ScreenSnapshot(InstagramCandidates.PACKAGE_NAME,nodes,app)
 @Test fun returningToObservedChatRearmsEachSharedReelEntry() {
  val classifier=CandidateInstagramClassifier()
  val thread=screen(listOf(UiNode("com.instagram.android:id/thread_fragment_container",bounds=Bounds(0,132,1080,2337)),
   UiNode("com.instagram.android:id/message_list",bounds=Bounds(0,279,1080,2251))))
  assertEquals(Classification.OTHER,classifier.classify(thread))
  val gate=BackGate(250)
  repeat(20) { entry ->
   val now=entry*1000L
   assertTrue(gate.observe(classifier.classify(screen(listOf(viewer)+actions)),true,true,now))
   assertTrue(gate.observe(Classification.REELS,true,true,now+250))
   assertFalse(gate.observe(classifier.classify(thread),true,true,now+500))
  }
 }
 @Test fun retainedChatWithViewerIsStillConflictingEvidence() {
  val mixed=screen(listOf(viewer,UiNode("com.instagram.android:id/message_list",bounds=app))+actions)
  assertEquals(Classification.UNKNOWN,CandidateInstagramClassifier().classify(mixed))
  assertNull(OverlayPolicy.viewerGuard(mixed,Settings(enabled=true,consent=true),true))
 }
}
