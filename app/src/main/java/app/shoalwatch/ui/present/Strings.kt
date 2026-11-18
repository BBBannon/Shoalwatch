package app.shoalwatch.ui.present

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * The words the presentation mappers use, behind one small wrapper so the mappers stay free of Compose and can be tested with
 * real resources. Numbers are always passed in as text (`toString()`), so digits never change with the phone's language.
 */
class Strings(private val resources: Resources) {
    fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int): String = resources.getQuantityString(id, count, count.toString())

    /** For a plural whose text takes the count as a number (`%1$d`), such as `pin_wrong`. */
    fun pluralNumber(@PluralsRes id: Int, count: Int): String = resources.getQuantityString(id, count, count)

    /** [count] as the first argument (as text), then [more]. */
    fun plural(@PluralsRes id: Int, count: Int, vararg more: Any): String = resources.getQuantityString(id, count, count.toString(), *more)
}
