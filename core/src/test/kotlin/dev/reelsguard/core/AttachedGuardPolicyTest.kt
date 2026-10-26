package dev.reelsguard.core
import org.junit.Assert.*
import org.junit.Test
class AttachedGuardPolicyTest {
 private val app=Bounds(0,0,1080,2400)
 private val parent=GuardWindow(10,WindowKind.APPLICATION,app)
 private val shade=GuardWindow(20,WindowKind.SYSTEM,app,active=true,focused=true)
 @Test fun shadeMayOmitTheStillRenderedParentFromAccessibilityWindows() {
  assertTrue(AttachedGuardPolicy.retain(10,app,true,listOf(shade)))
  assertTrue(AttachedGuardPolicy.retain(10,app,true,listOf(parent,shade)))
 }
 @Test fun foreignAppsKeyboardLockAndGeometryChangesCannotRetainGuard() {
  for(other in listOf(shade.copy(kind=WindowKind.APPLICATION),shade.copy(kind=WindowKind.INPUT),shade.copy(kind=WindowKind.OTHER),shade.copy(defaultDisplay=false))) {
   assertFalse(AttachedGuardPolicy.retain(10,app,true,listOf(parent,other)))
  }
  assertFalse(AttachedGuardPolicy.retain(10,app,false,listOf(shade)))
  assertFalse(AttachedGuardPolicy.retain(10,app,true,listOf(parent.copy(bounds=Bounds(0,0,540,1200)),shade)))
  assertFalse(AttachedGuardPolicy.retain(10,app,true,listOf(parent,shade.copy(active=false,focused=false))))
  assertFalse(AttachedGuardPolicy.retain(10,app,true,listOf(parent,shade,shade.copy(id=30,kind=WindowKind.APPLICATION))))
 }
}
