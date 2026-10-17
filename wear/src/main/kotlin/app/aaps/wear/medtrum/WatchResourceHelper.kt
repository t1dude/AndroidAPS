package app.aaps.wear.medtrum

import android.content.Context
import android.content.res.Configuration
import app.aaps.core.interfaces.resources.ResourceHelper
import java.util.Locale

/** Handover test: the texts the Medtrum driver asks for, from the watch app's resources. */
class WatchResourceHelper(private val context: Context) : ResourceHelper {

    override fun gs(id: Int): String = context.getString(id)

    override fun gs(id: Int, vararg args: Any?): String = context.getString(id, *args)

    override fun gq(id: Int, quantity: Int, vararg args: Any?): String = context.resources.getQuantityString(id, quantity, *args)

    override fun gsNotLocalised(id: Int, vararg args: Any?): String {
        val english = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return context.createConfigurationContext(english).getString(id, *args)
    }

    override fun shortTextMode(): Boolean = true
}
