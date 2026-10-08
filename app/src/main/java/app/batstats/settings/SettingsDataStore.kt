package app.batstats.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import java.io.File
import java.io.IOException

internal fun createSettingsDataStore(
    file: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = { file },
)

internal fun DataStore<Preferences>.withDefaultsOnReadFailure(): DataStore<Preferences> {
    val store = this
    return object : DataStore<Preferences> by store {
        override val data = store.data.catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
    }
}
