package com.self.sleepcollect

import android.app.Application
import com.self.sleepcollect.data.CollectRepository
import com.self.sleepcollect.data.local.AppDatabase
import com.self.sleepcollect.data.prefs.UserPreferences

class MiSleepApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var preferences: UserPreferences
        private set
    lateinit var repository: CollectRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
        preferences = UserPreferences(this)
        repository = CollectRepository(database.collectDao())
    }
}
