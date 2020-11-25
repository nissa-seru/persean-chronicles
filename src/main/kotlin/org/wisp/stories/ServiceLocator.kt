package org.wisp.stories

import wisp.questgiver.wispLib.CrashReporter
import wisp.questgiver.wispLib.ServiceLocator
import wisp.questgiver.wispLib.Text
import java.util.*


/**
 * Singleton instance of the service locator. Set a new one of these for unit tests.
 */
var game: SpaceTalesServiceLocator = SpaceTalesServiceLocator()

class SpaceTalesServiceLocator : ServiceLocator() {
    val errorReporter: CrashReporter =
        CrashReporter(modName = MOD_NAME, modAuthor = MOD_AUTHOR, game = this)

    val text = Text(
        resourceBundle = ResourceBundle.getBundle("SpaceTales")
    )
}