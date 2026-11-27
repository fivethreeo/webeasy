package no.webeasy.app

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UiTextTest {
    @Test
    fun interfaceUsesBokmalResourcesWithoutChangingEnglishOrCustomText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun language(locale: String) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
        )
        assertEquals("Sider", localizedUi(language("nb-NO"), "Pages"))
        assertEquals("Lagre", localizedUi(language("nb-NO"), "Save"))
        assertEquals("Pages", localizedUi(language("en"), "Pages"))
        assertEquals("Save", localizedUi(language("de"), "Save"))
        assertEquals("My own title", localizedUi(language("nb-NO"), "My own title"))
    }
}
