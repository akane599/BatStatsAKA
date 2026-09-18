package app.batstats.support

import app.batstats.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Exercise real English resource templates in JVM presentation tests without an Android runtime. */
object EnglishStrings {
    private val strings by lazy {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.exists() }
        val elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val names = (0 until elements.length).associate { index ->
            val element = elements.item(index) as org.w3c.dom.Element
            element.getAttribute("name") to element.textContent.replace("\\'", "'").replace("\\n", "\n")
        }
        R.string::class.java.fields.mapNotNull { field -> names[field.name]?.let { field.getInt(null) to it } }.toMap()
    }
    fun get(id: Int, arguments: Array<out Any>): String = String.format(Locale.US, strings.getValue(id), *arguments)
}
