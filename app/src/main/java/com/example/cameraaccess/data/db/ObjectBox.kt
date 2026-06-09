package com.example.cameraaccess.data.db

import android.content.Context
import android.util.Log
import com.example.cameraaccess.data.entities.MyObjectBox
import io.objectbox.BoxStore
import io.objectbox.exception.DbException

object ObjectBox {
    var store: BoxStore? = null
        private set

    fun init(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        store = try {
            openStore(app)
        } catch (e: DbException) {
            Log.e(TAG, "ObjectBox open failed (often schema mismatch or corruption); clearing DB and retrying once", e)
            BoxStore.deleteAllFiles(app, null)
            openStore(app)
        }
    }

    private fun openStore(app: Context): BoxStore =
        MyObjectBox.builder()
            .androidContext(app)
            .build()

    private const val TAG = "ObjectBox"
}
