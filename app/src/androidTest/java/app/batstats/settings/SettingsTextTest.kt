package app.batstats.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SettingsTextTest {
    @Test fun allVisibleSettingsResolveLocalizedTextWithoutChangingStoredSemantics() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val english = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val turkish = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("tr")) })
        AppSettingsSchema.fields.filter { it.meta != null }.forEach { field ->
            assertTrue("Missing title mapping: ${field.name}", SettingsText.titles.containsKey(field.name))
            val meta = field.meta!!
            val localized = SettingsText.resolve(turkish, field.name, meta)
            assertEquals(meta.key, localized.key)
            assertEquals(meta.type, localized.type)
            assertEquals(meta.options.size, localized.options.size)
            assertEquals(meta.min, localized.min, 0f)
            assertEquals(meta.max, localized.max, 0f)
        }
        val theme = AppSettingsSchema.fields.first { it.name == "themeIndex" }
        assertNotEquals(SettingsText.resolve(english, theme.name, theme.meta!!).title,
            SettingsText.resolve(turkish, theme.name, theme.meta!!).title)
    }
}
