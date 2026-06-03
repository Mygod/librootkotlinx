package be.mygod.librootkotlinx.impl

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Internal provider used only as a root-to-app Binder handoff endpoint during RootService startup.
 */
internal class RootServiceHandoffProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != RootServiceHandoff.METHOD) return super.call(method, arg, extras)
        val context = context
        return Bundle().apply {
            if (Binder.getCallingUid() != Process.ROOT_UID || context == null) {
                putBoolean(RootServiceHandoff.EXTRA_ACCEPTED, false)
                return@apply
            }
            val token = extras?.getString(RootServiceHandoff.EXTRA_TOKEN)
            val binder = extras?.getBinder(RootServiceHandoff.EXTRA_BINDER)
            putBoolean(RootServiceHandoff.EXTRA_ACCEPTED, RootServiceHandoff.deliver(token, binder))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
