package com.criptext

import android.app.Service
import android.content.*
import android.os.AsyncTask
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.content.LocalBroadcastManager
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import com.criptext.comunication.*
import com.criptext.database.CriptextDBHandler
import com.criptext.http.*
import com.criptext.lib.*
import com.criptext.security.AESUtil
import com.criptext.security.AsyncAESInitializer
import com.criptext.security.RandomStringBuilder
import com.google.gson.JsonObject
import org.apache.commons.io.FilenameUtils
import org.apache.http.client.ClientProtocolException
import org.apache.http.client.methods.HttpGet
import org.apache.http.params.BasicHttpParams
import org.apache.http.params.HttpConnectionParams
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

/**
 * Created by gesuwall on 5/25/16.
 */

abstract class MonkeyKitSocketService : Service() {

    /**
     * How many messages should have every batch from sync
     */
    var portionsMessages: Int = 15
    /**
     * A timestamp to send as argument for sync. Server will return messages sent after that timestamp
     * It should only be updated when a message is received.
     */
    var lastTimeSynced: Long = 0L
    set (value){
        if(status != ServiceStatus.dead && value > field)
            //only update last sync if service is not dead.
            //setting a lower value is not allowed. It should always increase
            field = value
    }
    /**
     * object that holds data about the app and the logged in user.
     */
    protected lateinit var clientData: ClientData
    /**
     * object used to encrypt, decrypt and generate AES Keys.
     */
    protected lateinit var aesutil: AESUtil
    /**
     * Object that periodically restarts the socket connection if messages were not successfuly delivered
     */
    protected var watchdog: Watchdog? = null
    /**
     * Object that manages the socket connection and runs it in a background thread.
     */
    protected lateinit var asyncConnSocket: AsyncConnSocket
    /**
     * Object that manage user methods over http
     */
    internal lateinit var userManager: UserManager
    /**
     * Object that manage group methods over http
     */
    internal lateinit var groupManager : GroupManager
    /**
     * Delegate object that will execute callbacks
     */
    var delegate: MonkeyKitDelegate? = null
    private set;
    /**
     * true if the service was started manually only for sync
     */
    var startedManually: Boolean = false
    private set
    /**
     * Keeps the CPU on even if the screen is turned off while  a lock is held
     */
    internal var wakeLock: PowerManager.WakeLock? = null

    /**
     * List of messages that have not been successfully delivered yet
     */
    private val pendingMessages: MutableList<JsonObject> = mutableListOf();

    var broadcastReceiver: BroadcastReceiver? = null

    val messageHandler: MOKMessageHandler by lazy {
        MOKMessageHandler(this)
    }

    internal var receiver : ConnectionChangeReceiver? = null

    fun downloadFile(fileMessageId: String, fileName: String, props: String, monkeyId: String){
        val intent = Intent(this, uploadServiceClass)
        intent.putExtra(MOKMessage.MSG_KEY, fileName)
        intent.putExtra(MOKMessage.PROPS_KEY, props)
        intent.putExtra(MOKMessage.SID_KEY, monkeyId)
        intent.putExtra(MOKMessage.ID_KEY, fileMessageId)
        intent.putExtra(MonkeyFileService.ISUPLOAD_KEY, false)
        intent.putExtra(MonkeyFileService.APPID_KEY, clientData.appId)
        intent.putExtra(MonkeyFileService.APPKEY_KEY, clientData.appKey)

        startService(intent)
    }

    private fun initializeMonkeyKitService(){
        status = ServiceStatus.initializing
        openDatabase();
        broadcastReceiver = FileBroadcastReceiver(this)
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver,
                IntentFilter(MonkeyFileService.UPLOAD_ACTION))
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver,
                IntentFilter(MonkeyFileService.DOWNLOAD_ACTION))
        val asyncAES = AsyncAESInitializer(this)
        asyncAES.execute()
        startConnectivityBoradcastReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startedManually = true
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MonkeyKitSocketService")
        wakeLock?.acquire();
        initializeMonkeyKitService()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        if(status == ServiceStatus.dead)
            initializeMonkeyKitService()
        else
            status = ServiceStatus.bound

        return MonkeyBinder()
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)

    }

    /**
     * This method gets called by the Async Intializer on its PostExecute method.
     */
    fun startSocketConnection(aesUtil: AESUtil, cdata: ClientData) {
        clientData = cdata
        userManager = UserManager(this, aesUtil)
        groupManager = GroupManager(this, aesUtil)
        this.aesutil = aesUtil
        startSocketConnection()
    }

    fun startSocketConnection() {
        //At this point initialization is complete. We are ready to receive and send messages
        status = if(delegate != null) ServiceStatus.bound else ServiceStatus.running

        asyncConnSocket = AsyncConnSocket(clientData, messageHandler, this);
        asyncConnSocket.conectSocket()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("MonkeyKitSocketService", "onUnbind");
        delegate = null
        status = ServiceStatus.running
        if(startedManually) { //if service started manually, stop it manually with a timeout task
            ServiceTimeoutTask(this).execute()
            return true
        } else
            return false
    }

    override fun onDestroy() {
        super.onDestroy()

        if(status >= ServiceStatus.running)
            asyncConnSocket.disconectSocket()

        status = ServiceStatus.dead
        Log.d("MonkeyKitSocketService", "onDestroy");
        //let the CPU go to sleep by releasing the wake lock
        releaseWakeLock()

        //If for some reason, client didn't unbind,
        delegate = null;
        watchdog?.cancel()
        //persist pending messages to a file
        if(pendingMessages.isNotEmpty()){
            //Log.d("serviceOnDestroy", "save messages")
            val task = PendingMessageStore.AsyncStoreTask(this, pendingMessages.toList())
            task.execute()
        }
        //unregister file broadcast receivers
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        //persist last time synced
        KeyStoreCriptext.setLastSync(this, lastTimeSynced)
        closeDatabase();
        //unregister connectivity change receiver
        if(receiver!=null)
            unregisterReceiver(receiver)
    }

    fun startConnectivityBoradcastReceiver(){

        if(receiver==null) {
            receiver = ConnectionChangeReceiver(this)
        }
        val conn_changereceived = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(receiver, conn_changereceived)
    }

    inner class MonkeyBinder : Binder() {

        fun getService(delegate: MonkeyKitDelegate): MonkeyKitSocketService{
            this@MonkeyKitSocketService.delegate = delegate
            Log.d("MonkeyKitSocketService", "set delegate. ${this@MonkeyKitSocketService.delegate != null}")
            return this@MonkeyKitSocketService;
        }

    }

    fun releaseWakeLock(){
        wakeLock?.release()
        wakeLock = null
    }

    fun addMessageToDecrypt(encrypted: MOKMessage) {
        //TODO ADD UNDECRYPTED MAYBE?
    }

    fun processMessageFromHandler(method:CBTypes, info:Array<Any>) {
        if(status < ServiceStatus.running)
            return //There's no point in doing anything with the delegates if the service is dead.

        when (method) {
            CBTypes.onAcknowledgeReceived -> {
                Log.d("MonkeyKitSocketService", "ack rec.")
                delegate?.onAcknowledgeRecieved(info[0] as String, info[1] as String, info[2] as String
                        , info[3] as String, info[4] as Boolean, info[5] as Int)
            }
            CBTypes.onConversationOpenResponse -> {
                delegate?.onConversationOpenResponse(info[0] as String, info[1] as Boolean, info[2] as String, info[3] as String, info[4] as String)
            }
            CBTypes.onSocketConnected -> {
                resendPendingMessages()
                delegate?.onSocketConnected()
                sendSync(lastTimeSynced)
            }
            CBTypes.onMessageReceived -> {
                val message = info[0] as MOKMessage
                val tipo = CriptextDBHandler.getMonkeyActionType(message);
                if(tipo == MessageTypes.blMessageAudio ||
                    tipo == MessageTypes.blMessagePhoto ||
                    tipo == MessageTypes.blMessageDocument ||
                    tipo == MessageTypes.blMessageScreenCapture ||
                    tipo == MessageTypes.blMessageShareAFriend ||
                    tipo == MessageTypes.blMessageDefault)
                    storeReceivedMessage(message, Runnable {
                        //Message received and stored, update lastTimeSynced with with the timestamp
                        //that the server gave the message
                        lastTimeSynced = message.datetime.toLong();
                        delegate?.onMessageRecieved(message)
                        if(startedManually && delegate == null)  //if service started manually, stop it manually with a timeout task
                            ServiceTimeoutTask(this).execute()
                    })
            }

            CBTypes.onMessageBatchReady -> {
                val batch = info[0] as ArrayList<MOKMessage>;
                storeMessageBatch(batch, Runnable {
                    //Message batch received and stored, update lastTimeSynced with with the timestamp
                    //that the server gave to the last message
                    if(batch.isNotEmpty())
                        lastTimeSynced = batch.last().datetime.toLong();
                    delegate?.onMessageBatchReady(batch);
                    if(startedManually && delegate == null)  //if service started manually, stop it manually with a timeout task
                        ServiceTimeoutTask(this).execute()
                });
            }

            CBTypes.onSocketDisconnected -> {
                delegate?.onSocketDisconnected()
            }/*
            CBTypes.onNetworkError -> {
                delegate?.onNetworkError();
            }*/
            CBTypes.onDeleteReceived -> {
                delegate?.onDeleteRecieved(info[0] as String, info[1] as String, info[2] as String, info[3] as String);
            }
            CBTypes.onCreateGroupOK -> {
                delegate?.onCreateGroupOK(info[0] as String);
            }
            CBTypes.onCreateGroupError -> {
                delegate?.onCreateGroupOK(info[0] as String);
            }
            CBTypes.onFileDownloadFinished -> {
                delegate?.onFileDownloadFinished(info[0] as String, info[1] as Boolean);
            }
            CBTypes.onDeleteGroupOK -> {
                delegate?.onDeleteGroupOK(info[0] as String);
            }
            CBTypes.onDeleteGroupError -> {
                delegate?.onDeleteGroupError(info[0] as String);
            }
            CBTypes.onContactOpenMyConversation -> {
                delegate?.onContactOpenMyConversation(info[0] as String);
            }
            CBTypes.onGetGroupInfoOK -> {
                delegate?.onGetGroupInfoOK(info[0] as JsonObject);
            }
            CBTypes.onGetGroupInfoError -> {
                delegate?.onGetGroupInfoError(info[0] as String);
            }
            CBTypes.onNotificationReceived -> {
                delegate?.onNotificationReceived(info[0] as String, info[1] as String, info[2] as String, info[3] as JsonObject, info[4] as String);
            }
            CBTypes.onMessageFailDecrypt -> {
                delegate?.onMessageFailDecrypt(info[0] as MOKMessage);
            }
            CBTypes.onGroupAdded -> {
                delegate?.onGroupAdded(info[0] as String, info[1] as String, info[2] as JsonObject);
            }
            CBTypes.onGroupNewMember -> {
                delegate?.onGroupNewMember(info[0] as String, info[1] as String);
            }
            CBTypes.onGroupRemovedMember -> {
                delegate?.onGroupRemovedMember(info[0] as String, info[1] as String);
            }
            CBTypes.onGroupsRecover -> {
                delegate?.onGroupsRecover(info[0] as String);
            }
            CBTypes.onFileFailsUpload -> {
                delegate?.onFileFailsUpload(info[0] as MOKMessage);
            }
            CBTypes.onConnectionRefused -> {
                delegate?.onConnectionRefused();
            }
        }
    }




    val serviceClientData: ClientData
        get() = clientData

    fun decryptAES(encryptedText: String) = aesutil.decrypt(encryptedText)

    fun isSocketConnected(): Boolean = status >= ServiceStatus.running && asyncConnSocket.isConnected

    fun notifySyncSuccess() {
        if(pendingMessages.isEmpty()){
            watchdog?.cancel()
            watchdog = null
        }
    }



     fun startWatchdog(){
        if(watchdog == null) {
            watchdog = Watchdog(this);
            watchdog!!.start();
        }
    }

    /**
     * Makes a copy of the current state of the pendingMessages list and sends through the socket
     * all the contained messages.
     */
    fun resendPendingMessages(){
        val messages = pendingMessages.toList()
        for(msg in messages)
            sendJsonThroughSocket(msg)
    }

    fun addPendingMessages(messages: List<JsonObject>){
        pendingMessages.addAll(0, messages)
    }

    /**
     * get the id of a message
     */
    fun getJsonMessageId(json: JsonObject) = json.get("args").asJsonObject.get("id").asString

    /**
     * Uses binary search to remove a message from the pending messages list.
     * @param id id of the message to remove
     */
    fun removePendingMessage(id: String){
        val index = pendingMessages.binarySearch { n ->
            id.compareTo(getJsonMessageId(n))
        }

        if(index > -1) {
            pendingMessages.removeAt(index)
            if(pendingMessages.isEmpty()) {
                watchdog?.cancel()
                watchdog = null
            }
        }
    }

    /**
     * Ads a message to the list of pending messages and starts the watchdog.
     * @param json mensaje a guardar
     * @throws JSONException
     */
    private fun addMessageToWatchdog(json: JsonObject) {
        pendingMessages.add(json);
        startWatchdog()
    }

    /**
     * Crea un nuevo MOKMessage con Id unico y con un timestamp actual. Al crear un nuevo MOKMessage
     * para ser enviado siempre debe de usarse este metodo en lugar del constructor por defecto que
     * tiene MOKMessage ya que inicializa varios atributos de la manera correcta para ser enviado.
     * @param textMessage texto a enviar en el mensaje
     * @param sessionIDTo session ID del destinatario
     * @param type tipo del mensaje. Debe de ser uno de los valores de MessageTypes.FileTypes
     * @param params JsonObject con parametros adicionales a enviar.
     * @return Una nueva instancia de MOK Message lista para ser enviada por el socket.
     */
    fun createMOKMessage(textMessage: String, sessionIDTo: String, type: Int, params: JsonObject): MOKMessage {
        val datetimeorder = System.currentTimeMillis();
        val datetime = datetimeorder/1000L;
        val srand = RandomStringBuilder.build(3);
        val idnegative = "-" + datetime;
        val message = MOKMessage(idnegative + srand, clientData.monkeyId, sessionIDTo, textMessage,
                "" + datetime, "" + type, params, JsonObject());
        message.datetimeorder = datetimeorder;
        return message;
    }

    private fun createSendProps(old_id: String, encrypted: Boolean): JsonObject {
        val props = JsonObject();
        props.addProperty("str", "0");
        props.addProperty("encr", if (encrypted) "1" else "0")
        props.addProperty("device", "android");
        props.addProperty("old_id", old_id);
        return props;
    }

    private fun createSendJSON(idnegative: String, sessionIDTo: String, elmensaje: String,
                               pushMessage: PushMessage, params: JsonObject, props: JsonObject, encrypted: Boolean): JsonObject {

        val args= JsonObject();
        val json= JsonObject();

        try {

            args.addProperty("id", idnegative);
            args.addProperty("rid", sessionIDTo);
            args.addProperty("msg", if (encrypted) aesutil.encrypt(elmensaje) else Base64.encodeToString(elmensaje.toByteArray(), Base64.NO_WRAP));
            args.addProperty("type", MessageTypes.MOKText);
            args.addProperty("push", pushMessage.toString());
            if (params != null)
                args.addProperty("params", params.toString());
            if (props != null) {
                if (!encrypted)
                    props.addProperty("encoding", "base64")
                args.addProperty("props", props.toString());
            }

            json.add("args", args);
            json.addProperty("cmd", MessageTypes.MOKProtocolMessage);
        } catch(ex: Exception){
            ex.printStackTrace();
        }

        return json;
    }

    fun sendJsonThroughSocket(json: JsonObject) {
        asyncConnSocket.sendMessage(json);
    }

    /**
     * Envia una notificación a traves de MonkeyKit. Las notificaciones no se persisten. Si el
     * destinatario no la pudo recibir a tiempo, no la recibira nunca
     * @param sessionIDTo session ID del usuario que recibira la notificacion
     * @param paramsObject JsonObject con parametros adicionales que necesita la aplicacion
     * @param pushMessage Mensaje a mostrar en el push notification
     */
    fun sendNotification(sessionIDTo: String, paramsObject: JSONObject, pushMessage: String) {

        try {
            val args = JsonObject()
            val json = JsonObject()

            val idNegative = "-" + System.currentTimeMillis() / 1000
            args.addProperty("id", idNegative)
            args.addProperty("sid", clientData.monkeyId)
            args.addProperty("rid", sessionIDTo)
            args.addProperty("params", paramsObject.toString())
            args.addProperty("type", MessageTypes.MOKNotif)
            args.addProperty("msg", "")
            args.addProperty("push", pushMessage.replace("\\\\", "\\"))

            val props = JsonObject()
            props.addProperty("old_id", idNegative)
            args.addProperty("props", props.toString())

            json.add("args", args)
            json.addProperty("cmd", MessageTypes.MOKProtocolMessage)

            if(isSocketConnected()){
                System.out.println("MONKEY - Enviando notificacion:" + json.toString())
                sendJsonThroughSocket(json)
            }
            else {
                System.out.println("MONKEY - no pudo enviar notificacion - socket desconectado " + asyncConnSocket.socketStatus);
                Thread.dumpStack()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    /**
     * Envia una notificación temporal. Si la notificacion no llega al destinatario con el primer intento
     * no se vuelve a enviar.
     * @param sessionIDTo session ID del destinatario de la notificacion
     * @param paramsObject JsonObject con parametros a enviar en la notificacion
     */
    fun sendTemporalNotification(sessionIDTo: String, paramsObject: JSONObject) {

        try {

            val args = JsonObject()
            val json = JsonObject()

            args.addProperty("rid", sessionIDTo)
            args.addProperty("params", paramsObject.toString())
            args.addProperty("type", MessageTypes.MOKTempNote)
            args.addProperty("msg", "")

            json.add("args", args)
            json.addProperty("cmd", MessageTypes.MOKProtocolMessage)

            if(isSocketConnected()){
                System.out.println("MONKEY - Enviando temp notificacion:" + json.toString())
                sendJsonThroughSocket(json)
            }
            else {
                System.out.println("MONKEY - no pudo enviar temp notificacion - socket desconectado " + asyncConnSocket.socketStatus);
                Thread.dumpStack()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    /**
     * Get info of a group or a user.
     * @param monkeyid monkeyid ID of the user or group.
     * @param monkeyJsonResponse callback to receive the response.
     */
    fun getInfoById(monkeyId: String, monkeyJsonResponse: MonkeyJsonResponse){
        userManager.getInfoById(monkeyId, monkeyJsonResponse)
    }

    /**
     * Update the metada of a user
     * @param monkeyid monkeyid ID of the user.
     * @param userInfo JSONObject that contains user data.
     * @param monkeyHttpResponse callback to receive the response.
     */
    fun updateUserObject(monkeyId: String, userInfo: JSONObject, monkeyHttpResponse: MonkeyHttpResponse) {
        userManager.updateUserObject(monkeyId,userInfo,monkeyHttpResponse)
    }

    /**
     * Get all conversation of a user using the monkey ID.
     * @param monkeyJsonResponse callback to receive the response.
     */
    fun getAllConversations(monkeyJsonResponse: MonkeyJsonResponse){
        userManager.getConversations(clientData.monkeyId, asyncConnSocket, monkeyJsonResponse)
    }

    /**
     * Get all messages of a conversation.
     * @param monkeyid monkeyid ID of the user.
     * @param monkeyJsonResponse callback to receive the response.
     */
    fun getConversationMessages(conversationId: String, numberOfMessages: Int, lastTimeStamp: String, monkeyJsonResponse: MonkeyJsonResponse){
        userManager.getConversationMessages(clientData.monkeyId, conversationId, numberOfMessages, lastTimeStamp, asyncConnSocket, monkeyJsonResponse)
    }

    /**
     * Create a group asynchronously and receive response via monkeyJsonResponse
     * @param members String with the sessionIDs of the members of the group.
     * @param group_name String with the group name
     * @param group_id String with the group id (optional)
     * @param monkeyJsonResponse callback to receive the response.
     */
    fun createGroup(members: String, group_name: String, group_id: String, monkeyJsonResponse: MonkeyJsonResponse){
        groupManager.createGroup(members, group_name, group_id, monkeyJsonResponse)
    }

    /**
     * Remove a group member asynchronously int the Monkey server. This method help you to delete yourself
     * of the group. Response is delivered via monkeyJsonResponse.
     * @param group_id ID of the group
     * @param monkey_id ID of member to delete
     * @param monkeyJsonResponse callback to receive the response.
     */
    fun removeGroupMember(group_id: String, monkey_id: String, monkeyJsonResponse: MonkeyJsonResponse){
        groupManager.removeGroupMember(group_id, monkey_id, monkeyJsonResponse)
    }

    /**
     * Add a member to a group asynchronously.
     * @param new_member Session ID of the new member
     * @param group_id ID of the group
     * @param monkeyJsonResponse callback to receive the response.
     */
    fun addGroupMember(new_member: String, group_id: String, monkeyJsonResponse: MonkeyJsonResponse){
        groupManager.addGroupMember(new_member, group_id, monkeyJsonResponse)
    }

    /**
     * Intenta decriptar un MOKMessage. Si no tiene llaves o estan mal, hace los requerimientos
     * pertinentes al servidor y recursivamente vuelve a intentar. Si no tiene llaves, las pide al server.
     * Si estan mal las llaves, pide las ultimas llaves al server, si son iguales a las que ya tiene,
     * pide al server el texto del mensaje encriptado con las ultimas llaves. Si nada de eso funciona
     * retorna null
     * @param message El mensaje a decriptar
     * *
     * @return El mensaje con su texto decriptado. Si no se pudo decriptar retorna null
     */

    fun getKeysAndDecryptMOKMessage(message: MOKMessage): MOKMessage? {
        if (asyncConnSocket != null) {
            return asyncConnSocket.getKeysAndDecryptMOKMessage(message, false)
        }
        return null
    }

    /**
     * Disconnect the socket immediately. Useful for reconnecting.
     */
    fun forceDisconnect(){
        if(isSocketConnected()){
            asyncConnSocket.sendDisconectFromPull()
            delegate?.onSocketDisconnected()
        } else {
            Log.d("forceDisconnect", "${asyncConnSocket.socketStatus}")
            startSocketConnection();
        }
    }

    fun sendMessage(newMessage: MOKMessage, pushMessage: PushMessage, encrypted: Boolean): MOKMessage{

            try {

                val props = createSendProps(newMessage.message_id!!, encrypted);
                newMessage.props = props;


                val json= createSendJSON(newMessage.message_id, newMessage.rid, newMessage.msg, pushMessage,
                        newMessage.params ?: JsonObject(), props, encrypted);


                addMessageToWatchdog(json);
                sendJsonThroughSocket(json);

            }
            catch (e: Exception) {
                e.printStackTrace();
                return newMessage;
            }

        return newMessage;
    }

    fun sendFileMessage(newMessage: MOKMessage, pushMessage: PushMessage, encrypted: Boolean){
        val intent = Intent(this, uploadServiceClass)
        newMessage.toIntent(intent)
        intent.putExtra(MonkeyFileService.ISUPLOAD_KEY, true)
        intent.putExtra(MonkeyFileService.PUSH_KEY, pushMessage.toString())
        intent.putExtra(MonkeyFileService.ENCR_KEY, encrypted)
        intent.putExtra(MonkeyFileService.APPID_KEY, clientData.appId)
        intent.putExtra(MonkeyFileService.APPKEY_KEY, clientData.appKey)

        startService(intent)
    }

    private fun createSendProps(old_id: String, filepath: String, fileType: Int, originalSize: Int, encrypted: Boolean): JsonObject{
        val props = JsonObject();
        props.addProperty("str", "0");
        props.addProperty("encr", if (encrypted) "1" else "0")
        props.addProperty("device", "android");
        props.addProperty("old_id", old_id);

        val ext = FilenameUtils.getExtension(filepath)
        props.addProperty("cmpr", "gzip");
        props.addProperty("file_type", fileType);
        props.addProperty("ext", ext);
        props.addProperty("filename", FilenameUtils.getName(filepath));
        props.addProperty("mime_type", MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext));
        props.addProperty("size", originalSize);
        return props;
    }

    /**
     * Notifies the MonkeyKit server that the current user has opened an UI with conversation with
     * another user or a group. The server will notify the other party and will return any necessary
     * keys for decrypting messages sent by the other party.
     *
     * This method can also be used to retrieve any missing AES keys.
     */
    fun sendOpenConversation(conversationID: String){

    }

    fun requestKeysForMessage(encryptedMessage: MOKMessage){

        fun filter(list: MutableList<MOKMessage>, predicate: (MOKMessage) -> Boolean): List<MOKMessage>{
            val filtered = mutableListOf<MOKMessage>()
            var cont = 0
            for(item in list){
                if(predicate.invoke(item)){
                    filtered.add(list.removeAt(cont))
                }
                cont++
            }

            return filtered.toList()
        }


            //val idPredicate: (MOKMessage) -> Boolean =  { it -> it.sid == first.sid }
            //val sameConversationMsgs = filter(pendingMessages, idPredicate)
            val task = OpenConversationTask(this, encryptedMessage)
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, encryptedMessage.sid) //LAME
    }

    fun sendSync(lastTimeSync: Long){
        try {

           val args = JsonObject();
           val json = JsonObject();

            args.addProperty("since", lastTimeSync);

            if(lastTimeSync == 0L) {
                args.addProperty("groups", 1);
            }
            args.addProperty("qty", ""+ portionsMessages);
            json.add("args", args);
            json.addProperty("cmd", MessageTypes.MOKProtocolSync);

            if(isSocketConnected()){
                System.out.println("MONKEY - Enviando Sync:"+json.toString());
                sendJsonThroughSocket(json)
            }
            else {
                System.out.println("MONKEY - no pudo enviar Sync - socket desconectado " + asyncConnSocket.socketStatus);
                //Thread.dumpStack();
            }



        } catch (e: Exception) {
            e.printStackTrace();
        }
    }

    fun sendGet(since: String){

        try {

            val args = JsonObject();
            val json = JsonObject();

            args.addProperty("messages_since", since);
            if(since.equals("0")) {
                args.addProperty("groups", 1);
            }
            args.addProperty("qty", "" + portionsMessages);
            json.add("args", args);
            json.addProperty("cmd", MessageTypes.MOKProtocolGet);

            if(isSocketConnected())
                sendJsonThroughSocket(json)

            startWatchdog()

        } catch (e: Exception) {
            e.printStackTrace();
        }


        lastTimeSynced = since.toLong();
    }

    /**
     * Manda un requerimiento HTTP a Monkey para obtener las llaves mas recientes de un usuario que
     * tiene el server. Esta funcion debe de ser llamada en background de lo contrario lanza una excepcion.
     * @param sessionIdTo El session id del usuario cuyas llaves se desean obtener
     * @return Un String con las llaves del usuario. Antes de retornar el resultado, las llaves se
     * guardan en el KeyStoreCriptext.
     */
    fun requestKeyBySession(sessionIdTo: String): String?{
        // Create a new HttpClient and Post Header
        val httpclient = MonkeyHttpClient.newClient()

        try {

            val httppost = MonkeyHttpClient.newPost(httpsURL + "/user/key/exchange",
                    serviceClientData.appId, serviceClientData.appKey)
            val localJSONObject1 = JSONObject()
            val params = JSONObject()
            localJSONObject1.put("user_to",sessionIdTo);
            localJSONObject1.put("session_id",serviceClientData.monkeyId);
            params.put("data", localJSONObject1.toString());
            Log.d("OpenConversation", "Req: " + params.toString());

            val finalResult = MonkeyHttpClient.getResponse(httpclient, httppost, params.toString());
            Log.d("OpenConversation", finalResult.toString());
            val newKeys = decryptAES(finalResult.getJSONObject("data").getString("convKey"));
            KeyStoreCriptext.putStringBlocking(this, sessionIdTo, newKeys);
            return newKeys;

        } catch (ex: JSONException) {
            ex.printStackTrace();
        } catch (e: ClientProtocolException) {
            e.printStackTrace();
        } catch (e: IOException) {
            e.printStackTrace();
            // TODO Auto-generated catch block
        } catch (e: Exception){
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Manda un requerimiento HTTP a Monkey para obtener el texto de un mensaje encriptado con
     * las ultimas llaves del remitente que tiene el server. Esta funcion debe de ser llamada en
     * background de lo contrario lanza una excepcion
     * @param messageId Id del mensaje cuyo texto se quiere obtener
     * @return Un String con el texto encriptado con las llaves mas recientes.
     */
    public fun requestTextWithLatestKeys(messageId: String): String?{
        val httpParams = BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(httpParams, 20000);
        HttpConnectionParams.setSoTimeout(httpParams, 25000);
        // Create a new HttpClient and Post Header
        val httpclient = MonkeyHttpClient.newClient();
        val httppost = HttpGet("$httpsURL/message/$messageId/open/secure");

        try {

            val base64EncodedCredentials = "Basic " + Base64.encodeToString(
                    (serviceClientData.password).toByteArray(),
                    Base64.NO_WRAP);


            httppost.setHeader("Authorization", base64EncodedCredentials);

            Log.d("OpenSecure", "Req: " + messageId);
            //sets a request header so the page receving the request
            //will know what to do with it
            // Execute HTTP Post Request
            val response = httpclient.execute(httppost);
            val reader = BufferedReader(InputStreamReader(response.entity.content, "UTF-8"));
            val json = reader.readLine();
            val tokener = JSONTokener(json);
            val finalResult = JSONObject(tokener);

            Log.d("OpenSecure", finalResult.toString());
            val newEncryptedMessage = finalResult.getJSONObject("data").getString("message");
            Log.d("OpenSecure", newEncryptedMessage);
            return newEncryptedMessage;

        } catch (ex: JSONException) {
            ex.printStackTrace();
        } catch (ex: ClientProtocolException) {
            ex.printStackTrace();
        } catch (ex: IOException) {
            ex.printStackTrace();
        }

        return null;
    }

    abstract val uploadServiceClass: Class<*>

    abstract fun openDatabase()

    abstract fun closeDatabase()

    /**
     * Guarda un mensaje de MonkeyKit en la base de datos. La implementacion de este metodo deberia de
     * ser asincrona para mejorar el rendimiento del servicio. MonkeyKit llamara a este metodo cada
     * vez que reciba un mensaje para guardarlo.
     * @param message
     * @param incoming
     * @param runnable Este runnable debe ejecutarse despues de guardar el mensaje
     */
    abstract fun storeReceivedMessage(message: MOKMessage, runnable: Runnable)

    /**
     * Guarda un grupo de mensajes de MonkeyKit que se recibieron despues de un sync en la base de datos.
     * Es sumamente importante implementar esto de forma asincrona porque potencialmente, podrian
     * llegar cientos de mensajes, haciendo la operacion sumamente costosa.
     * @param messages
     * @param runnable Este runnable debe ejecutarse despues de guardar el batch de mensajes
     */
    abstract fun storeMessageBatch(messages: ArrayList<MOKMessage>, runnable: Runnable);

    /**
     * Loads all credentials needed to initialize the service. This method will be called in
     * a background thread, so it's ok to do blocking operations.
     */
    abstract fun loadClientData(): ClientData

    companion object {
        val transitionMessagesPrefs = "MonkeyKit.transitionMessages";
        val lastSyncPrefs = "MonkeyKit.lastSyncTime";
        val lastSyncKey = "MonkeyKit.lastSyncKey";

        val baseURL = "stage.monkey.criptext.com"
        val httpsURL = "http://" + baseURL
        val SYNC_SERVICE_KEY = "SecureSocketService.SyncService"

        var status = ServiceStatus.dead

        fun bindMonkeyService(context:Context, connection: ServiceConnection, service:Class<*>) {
            val intent = Intent(context, service)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    enum class ServiceStatus {
        dead, initializing, running, bound
    }

}
