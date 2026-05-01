package moe.antimony.hoshi

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MainActivityConfigurationTest {
    @Test
    fun mainActivityHandlesReaderOrientationChangesInPlace() {
        val activity = mainActivityManifestElement()
        val configChanges = activity
            .getAttribute("android:configChanges")
            .split('|')
            .filter { it.isNotBlank() }
            .toSet()

        assertTrue("MainActivity must keep the reader host alive on rotation.", "orientation" in configChanges)
        assertTrue("MainActivity must also handle the screen size change emitted with rotation.", "screenSize" in configChanges)
    }

    private fun mainActivityManifestElement(): Element {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val element = activities.item(index) as Element
            if (element.getAttribute("android:name") == ".MainActivity") {
                return element
            }
        }
        error("MainActivity not found in AndroidManifest.xml")
    }
}
