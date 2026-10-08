package com.joy.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.joySessionStore: DataStore<Preferences> by preferencesDataStore(name = "joy_sessions")

class SessionStore(private val context: Context) {
    private object Keys {
        val snapshot = stringPreferencesKey("session_snapshot")
    }

    val snapshot: Flow<SessionSnapshot> = context.joySessionStore.data.map { prefs ->
        SessionSnapshot.fromJson(prefs[Keys.snapshot])
    }

    suspend fun read(): SessionSnapshot = snapshot.first()

    suspend fun write(snapshot: SessionSnapshot) {
        val normalized = normalize(snapshot)
        context.joySessionStore.edit { prefs ->
            prefs[Keys.snapshot] = SessionSnapshot.encode(normalized)
        }
    }

    companion object {
        const val MAX_SESSIONS = 40

        fun normalize(snapshot: SessionSnapshot): SessionSnapshot {
            var list = snapshot.sessions
                .distinctBy { it.id }
                .sortedByDescending { it.updatedAt }
                .take(MAX_SESSIONS)
            if (list.isEmpty()) {
                return SessionSnapshot.bootstrap()
            }
            val activeId =
                if (list.any { it.id == snapshot.activeId }) {
                    snapshot.activeId
                } else {
                    list.first().id
                }
            // 保持列表顺序：最近在前
            return SessionSnapshot(activeId = activeId, sessions = list)
        }
    }
}
