package dev.reelsguard

import android.app.Application
import dev.reelsguard.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.reelsguard.core.UsageState

class ReelsGuardApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository by lazy { AppRepository(this, scope) }
    fun checkpoint(usage: UsageState) { scope.launch { repository.saveUsage(usage) } }
}
