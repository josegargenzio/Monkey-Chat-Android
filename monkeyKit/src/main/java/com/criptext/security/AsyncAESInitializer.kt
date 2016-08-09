package com.criptext.security

import android.content.Context
import android.content.SharedPreferences
import android.os.AsyncTask
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import com.criptext.ClientData
import com.criptext.MonkeyKitSocketService
import com.criptext.lib.KeyStoreCriptext
import com.criptext.lib.PendingMessageStore

import com.google.gson.JsonObject

import java.lang.ref.WeakReference

/**
 * Asynchronously performs operations needed to start a socket service. The list of operations is:
 * - load from disk text messages that have not been yet successfully sent
 * - load from disk the timestamp from the last sync
 * - Initialize the AESUtil object
 * the onPostExecute method must call call startSocketConnection at the end.
 * Created by gesuwall on 6/1/16.
 */
class AsyncAESInitializer(socketService: MonkeyKitSocketService) : AsyncTask<Void, Void, AsyncAESInitializer.InitializerResult>() {
    internal var socketServiceRef: WeakReference<MonkeyKitSocketService>
    private val isSyncService: Boolean

    init {
        socketServiceRef = WeakReference(socketService)
        isSyncService = socketService.startedManually
                && MonkeyKitSocketService.status != MonkeyKitSocketService.ServiceStatus.bound
    }

    val clientData: ClientData
    get() = socketServiceRef.get()!!.loadClientData()

    override fun doInBackground(vararg voids: Void): InitializerResult? {
            //load client data, this may be expensive
            val cData = clientData
            Log.d("AsyncInitialzer", "mid: ${cData.monkeyId}")
            val context = socketServiceRef.get()
            var pendingMessages: List<JsonObject>? = null
            if(!isSyncService) //get pending messages if this service does more than just sync
                pendingMessages = PendingMessageStore.retrieve(context)
            //get the last sync timestamp from shared prefs
            val lastSync = KeyStoreCriptext.getLastSync(context)
            return InitializerResult(pendingMessages, AESUtil(context, cData.monkeyId), lastSync, cData)
    }

    override fun onPostExecute(result: InitializerResult?) {
        val service = socketServiceRef.get()
        val messages = result!!.pendingMessages
        if(messages != null && messages.isNotEmpty())
            service.addPendingMessages(messages)
        service?.lastTimeSynced = result.lastSync
        service?.startSocketConnection(result.util, result.clientData)
    }

    data class InitializerResult(val pendingMessages: List<JsonObject>?, val util: AESUtil,
                                 val lastSync: Long, val clientData: ClientData)


}
