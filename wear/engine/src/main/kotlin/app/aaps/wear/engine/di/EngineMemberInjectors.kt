package app.aaps.wear.engine.di

import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.wear.engine.medtrum.MedtrumWatchActivity
import app.aaps.wear.engine.medtrum.MedtrumWatchAlarmReceiver
import app.aaps.wear.engine.receivers.CarbSuggestionReceiver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

/** The engine's own Android entry points, which Android builds and which then fill their own fields. */
@ContributesTo(AppScope::class)
@BindingContainer
object EngineMemberInjectors {

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(MedtrumWatchActivity::class)
    fun bindMedtrumWatchActivity(injector: MembersInjector<MedtrumWatchActivity>): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(MedtrumWatchAlarmReceiver::class)
    fun bindMedtrumWatchAlarmReceiver(injector: MembersInjector<MedtrumWatchAlarmReceiver>): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(CarbSuggestionReceiver::class)
    fun bindCarbSuggestionReceiver(injector: MembersInjector<CarbSuggestionReceiver>): MembersInjector<*> = injector
}
