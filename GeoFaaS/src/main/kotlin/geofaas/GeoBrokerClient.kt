package geofaas

import com.google.gson.Gson
import de.hasenburg.geobroker.client.main.SimpleClient
import de.hasenburg.geobroker.commons.communication.ZMQProcessManager
import de.hasenburg.geobroker.commons.model.message.Payload
import de.hasenburg.geobroker.commons.model.message.ReasonCode
import de.hasenburg.geobroker.commons.model.message.Topic
import de.hasenburg.geobroker.commons.model.spatial.Geofence
import de.hasenburg.geobroker.commons.model.spatial.Location
import de.hasenburg.geobroker.commons.setLogLevel
import geofaas.Model.FunctionMessage
import geofaas.Model.ListeningTopic
import geofaas.Model.ClientType
import geofaas.Model.FunctionAction
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager

// Basic Geobroker client for GeoFaaS system
abstract class GeoBrokerClient(val location: Location, val mode: ClientType, debug: Boolean, host: String = "localhost", port: Int = 5559, val id: String = "GeoFaaSAbstract") {
    val logger = LogManager.getLogger()
    private var listeningTopics = mutableSetOf<ListeningTopic>()
    private val processManager = ZMQProcessManager()
    var remoteGeoBroker = SimpleClient(host, port, identity = id)
    val gson = Gson()
    init {
        if (debug) { setLogLevel(logger, Level.DEBUG) }
        remoteGeoBroker.send(Payload.CONNECTPayload(location)) // connect
        var connAck = remoteGeoBroker.receiveWithTimeout(3000)

        if (connAck is Payload.DISCONNECTPayload) {
            if(connAck.brokerInfo == null) { // retry with suggested broker
                logger.fatal("No responsible broker found. $id can't connect to the remote geoBroker '$host:$port', ${connAck.reasonCode}!!")
                throw RuntimeException("Error while connecting to the geoBroker")
            } else {
                logger.warn("Changed the remote broker to the suggested: ${connAck.brokerInfo}")
                remoteGeoBroker = SimpleClient(connAck.brokerInfo!!.ip, connAck.brokerInfo!!.port, identity = id)
                remoteGeoBroker.send(Payload.CONNECTPayload(location)) // connect
                connAck = remoteGeoBroker.receiveWithTimeout(3000)
            }
            if (connAck is Payload.DISCONNECTPayload) {
                logger.fatal("${connAck.reasonCode}! Failed to connect to suggested server! another suggested server? ${connAck.brokerInfo}")
                throw RuntimeException("Error connecting to the new geoBroker")
            }
        } else if (connAck == null) {
            throw RuntimeException("Error can't connect to geobroker $host:$port. Check the Address and try again")
        } else if (connAck !is Payload.CONNACKPayload || connAck.reasonCode != ReasonCode.Success){
            logger.error("Unexpected 'Conn ACK'! Received geoBroker's answer: {}", connAck)
            throw RuntimeException("Error while connecting to the geoBroker")
        }
    }

    fun subscribeFunction(funcName: String, fence: Geofence): MutableSet<ListeningTopic>? {
        logger.debug("subscribeFunction() call. params:'{}', '{}'", funcName, fence)
        var newTopics: MutableSet<ListeningTopic> = mutableSetOf()
        var baseTopic = "functions/$funcName"
        baseTopic += when (mode) {
            ClientType.EDGE, ClientType.CLOUD   -> "/call"
            ClientType.CLIENT -> "/result"
        }
        val topic = Topic(baseTopic)
        val newSubscribe = subscribe(topic, fence) //subscribe(baseTopic, fence, functionAction)
        if (newSubscribe != null) { newTopics.add(ListeningTopic(topic, fence)) }

        if (mode == ClientType.CLIENT && newSubscribe != null) { // Client subscribes to two topics
            val ackTopic = Topic("functions/$funcName/ack")
            val ackSubscribe = subscribe(ackTopic, fence)
            if (ackSubscribe != null) { newTopics.add(ListeningTopic(ackTopic, fence)) }
        }
        if (mode == ClientType.CLOUD && newSubscribe != null) { // Cloud subscribes to two topics
            val ackTopic = Topic("functions/$funcName/nack")
            val ackSubscribe = subscribe(ackTopic, fence)
            if (ackSubscribe != null) { newTopics.add(ListeningTopic(ackTopic, fence)) }
        }
        return if (newTopics.isNotEmpty()) {
            newTopics.forEach {  listeningTopics.add(it) } // add to local registry
            logger.debug("ListeningTopics appended by: {}", listeningTopics)
            newTopics // for error handling purposes
        } else {
            logger.debug("ListeningTopics didn't change. Nothing subscribed new!")
            null
        }
    }

    private fun subscribe(topic: Topic, fence: Geofence): ListeningTopic? {
        if (!isSubscribedTo(topic.topic)) {
            remoteGeoBroker.send(Payload.SUBSCRIBEPayload(topic, fence))
            val subAck = remoteGeoBroker.receiveWithTimeout(3000)
            if (subAck is Payload.SUBACKPayload){
                if (subAck.reasonCode == ReasonCode.GrantedQoS0){
                    logger.info("GeoBroker's Sub ACK for id:  for '${topic.topic}' in $fence: {}", subAck)
                    return ListeningTopic(topic, fence)
                } else { logger.error("Error Subscribing to '${topic.topic}' by $id. Reason: {}.", subAck.reasonCode) }
            }
        } else {
            logger.error("already subscribed to the '${topic.topic}'")
        }
        return null
    }

    fun listenFor(type: String, timeout: Int): FunctionMessage? {
        // function call
        logger.info("Listening to the geoBroker server for a '$type'...")
        val msg: Payload? = when (timeout){
            0 -> remoteGeoBroker.receive() // blocking
            else -> remoteGeoBroker.receiveWithTimeout(timeout)
        }
        if (timeout > 0 && msg == null) {
            logger.error("Listening timeout (${timeout}ms)!")
            return null
        }
        logger.info("EVENT from geoBroker: {}", msg)
        if (msg is Payload.PUBLISHPayload) {
// wiki:    msg.topic    => Topic(topic=functions/f1/call)
// wiki:    msg.content  => message
// wiki:    msg.geofence => BUFFER (POINT (0 0), 2)
            val topic = msg.topic.topic.split("/")
            if(topic.first() == "functions") {
                val message = gson.fromJson(msg.content, FunctionMessage::class.java)
                return message
//                return FunctionMessage(funcName, FunctionAction.valueOf(funcAction), msg.content, Model.TypeCode.Piggy)
            } else {
                logger.error("msg is not related to the functions! {}", msg.topic.topic)
                return null
            }
        } else {
            logger.error("Unexpected geoBroker message (not a PUBLISHPayload): $msg")
            return null
        }
    }

    // returns a map of function name to either call, result, or ack/nack
    fun subscribedFunctionsList(): Map<String, List<String>> {
        val functionCalls = listeningTopics.map { pair -> pair.topic.topic }//.filter { it.endsWith("/call") }
        logger.debug("functions that $id already listening to: {}", functionCalls)
        return functionCalls.map { val partialTopic = it.substringAfter("/").split("/");
        listOf(partialTopic.first(), partialTopic[1])}.groupBy { it.first() }.mapValues { it.value.map { pair -> pair[1] } }// take name of function and the action between '/', e.g. functions/"f1/call"
    }

    // follow geoBroker instructions to Disconnect
    fun terminate() {
        remoteGeoBroker.send(Payload.DISCONNECTPayload(ReasonCode.NormalDisconnection)) // disconnect
        remoteGeoBroker.tearDownClient()
        if (processManager.tearDown(3000)) {
            logger.info("GBClient Channel shut down properly.")
        } else {
            logger.fatal("ProcessManager reported that processes are still running: {}",
                processManager.incompleteZMQProcesses)
        }
//        exitProcess(0) // terminates current process
    }

     private fun isSubscribedTo(topic: String): Boolean { // NOTE: checks only the topic, not the fence
        return listeningTopics.map { pair -> pair.topic.topic }.any { it == topic }
     }

    protected fun processPublishAckSuccess(pubAck: Payload?, funcName: String, funcAct: FunctionAction, withTimeout: Boolean): Boolean {
        val logMsg = "GeoBroker's 'Publish ACK' for the '$funcName' $funcAct by $id: {}"
        if (pubAck is Payload.PUBACKPayload) {
            val noError = logPublishAck(pubAck, logMsg) // logs the reasonCode
            if (!noError) return false
        } else if (pubAck == null && withTimeout) {
            logger.error("Timeout! no 'Publish ACK' received for '$funcName' $funcAct by $id")
            return false
        } else {
            logger.error("Unexpected! $logMsg", pubAck)
        }
        return true
    }
    protected fun logPublishAck(pubAck: Payload.PUBACKPayload, logMsg: String): Boolean {
        // logMsg: "GeoBroker's 'Publish ACK' for the '$funcName' ACK by $id: {}"
        when (pubAck.reasonCode) {
            ReasonCode.GrantedQoS0 -> logger.info(logMsg, pubAck)
            ReasonCode.Success -> logger.info(logMsg, pubAck)
            ReasonCode.NoMatchingSubscribersButForwarded -> logger.warn(logMsg, pubAck.reasonCode)
            ReasonCode.NoMatchingSubscribers -> {
                logger.error("$logMsg. Terminating...", pubAck.reasonCode)
                return false
            }
            ReasonCode.NotConnectedOrNoLocation -> {
                logger.error(logMsg, pubAck)
                return false
            }
            else -> logger.warn(logMsg, pubAck)
        }
        return true
    }
}