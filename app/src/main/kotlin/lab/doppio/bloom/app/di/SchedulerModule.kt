package lab.doppio.bloom.app.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import lab.doppio.bloom.app.outreach.Scheduler
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/** Scope for work started from the dashboard (discovery, research). */
val BACKGROUND = named("background")

/** Scope of the scheduler loops. */
val LOOPS = named("loops")

/** The background loops and the two scopes they and the dashboard run in. Closing Koin cancels both. */
val schedulerModule: Module = module {
    single(LOOPS) { CoroutineScope(SupervisorJob() + Dispatchers.Default) } onClose { it?.cancel() }
    single(BACKGROUND) { CoroutineScope(SupervisorJob() + Dispatchers.Default) } onClose { it?.cancel() }
    single { Scheduler(get(), get(), get(), get(), get(), get(), intervals = get()) }
}
