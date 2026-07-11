package de.kilian.applimit.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore(
    name = "onboarding_status",
)

class OnboardingPreferences(
    private val context: Context,
) {
    data class State(
        val restrictedSettingsConfirmed: Boolean = false,
        val neverSleepingAppConfirmed: Boolean = false,
    )

    val state: Flow<State> = context.onboardingDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            State(
                restrictedSettingsConfirmed = preferences[RESTRICTED_SETTINGS_CONFIRMED]
                    ?: false,
                neverSleepingAppConfirmed = preferences[NEVER_SLEEPING_APP_CONFIRMED]
                    ?: false,
            )
        }

    suspend fun setRestrictedSettingsConfirmed(confirmed: Boolean) {
        context.onboardingDataStore.edit { preferences ->
            preferences[RESTRICTED_SETTINGS_CONFIRMED] = confirmed
        }
    }

    suspend fun setNeverSleepingAppConfirmed(confirmed: Boolean) {
        context.onboardingDataStore.edit { preferences ->
            preferences[NEVER_SLEEPING_APP_CONFIRMED] = confirmed
        }
    }

    private companion object {
        val RESTRICTED_SETTINGS_CONFIRMED = booleanPreferencesKey(
            "restricted_settings_confirmed",
        )
        val NEVER_SLEEPING_APP_CONFIRMED = booleanPreferencesKey(
            "never_sleeping_app_confirmed",
        )
    }
}
