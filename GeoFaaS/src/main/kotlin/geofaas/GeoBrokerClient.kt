package geofaas

import com.google.gson.Gson
import de.hasenburg.geobroker.client.main.SimpleClient
import de.hasenburg.geobroker.commons.communication.ZMQProcessManager
import de.hasenburg.geobroker.commons.model.disgb.BrokerInfo
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
import geofaas.Model.StatusCode
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import java.util.*

// Basic Geobroker client for GeoFaaS system
abstract class GeoBrokerClient(var location: Location, val mode: ClientType, debug: Boolean, host: String = "localhost", port: Int = 5559, val id: String = "GeoFaaSAbstract") {
    private val logger = LogManager.getLogger()
    private val processManager = ZMQProcessManager()
    private var listeningTopics = mutableSetOf<ListeningTopic>()
    protected val ackQueue : Queue<Payload> = LinkedList<Payload>()
    protected val pubQueue : Queue<Payload> = LinkedList<Payload>()
    var gbSimpleClient = SimpleClient(host, port, identity = id)
    val gson = Gson()
    init {
        if (debug) { setLogLevel(logger, Level.DEBUG) }
        gbSimpleClient.send(Payload.CONNECTPayload(location)) // connect
        var connAck = gbSimpleClient.receiveWithTimeout(8000)

        val connSuccess = processConnAckSuccess(connAck, BrokerInfo(gbSimpleClient.identity, host, port), true)
        if (!connSuccess) {
            if (connAck is Payload.DISCONNECTPayload) {
                if(connAck.brokerInfo == null) { // retry with suggested broker
                    if(connAck.reasonCode == ReasonCode.ProtocolError)
                        logger.fatal("Duplicate ids! $id can't connect to the remote geoBroker '$host:$port'.")
                    else if (connAck.reasonCode == ReasonCode.WrongBroker)
                        logger.fatal("Unexpected '${connAck.reasonCode}' while $id tried to connect to the remote geoBroker '$host:$port'.")
                    else
                        logger.fatal("Duplicate ids! $id can't connect to the remote geoBroker '$host:$port'.")
                    throw RuntimeException("Error while connecting to the geoBroker")
                } else {
                    val newBrokerInfo = connAck.brokerInfo!! // TODO replace with 'changeBroker()' and do the retry
//                    val changeIsSuccess = changeBroker(newBrokerInfo)
                    logger.warn("Changed the remote broker to the suggested: $newBrokerInfo")
                    gbSimpleClient = SimpleClient(newBrokerInfo.ip, newBrokerInfo.port, identity = id)
                    gbSimpleClient.send(Payload.CONNECTPayload(location)) // connect
                    connAck = gbSimpleClient.receiveWithTimeout(8000)
                    val connSuccess = processConnAckSuccess(connAck, newBrokerInfo, true)
                    if (!connSuccess)
                        throw RuntimeException("Error connecting to the new geoBroker")
                }
            } else if (connAck == null) {
                throw RuntimeException("Timeout! can't connect to geobroker $host:$port. Check the Address and try again")
            } else {
                logger.fatal("Unexpected 'Conn ACK'! Received geoBroker's answer: {}", connAck)
                throw RuntimeException("Error while connecting to the geoBroker")
            }
        }
    }

    //Returns the new topics (w/ fence) listening to
    fun subscribeFunction(funcName: String, fence: Geofence): MutableSet<ListeningTopic>? {
        logger.debug("subscribeFunction() call. params:'{}', '{}'", funcName, fence)
        var newTopics: MutableSet<ListeningTopic> = mutableSetOf()
        var baseTopic = "functions/$funcName"
        baseTopic += when (mode) {
            ClientType.EDGE, ClientType.CLOUD   -> "/call"
            ClientType.CLIENT -> "/result"
        }
        val topic = Topic(baseTopic)
        val newSubscribe: StatusCode = subscribe(topic, fence)
        if (newSubscribe == StatusCode.Success) { newTopics.add(ListeningTopic(topic, fence)) }

        if (newSubscribe != StatusCode.Failure) {
            if (mode == ClientType.CLIENT) { // Client subscribes to two topics
                val ackTopic = Topic("functions/$funcName/ack")
                val ackSubscribe = subscribe(ackTopic, fence)
                if (ackSubscribe == StatusCode.Success) { newTopics.add(ListeningTopic(ackTopic, fence)) }
            }
            if (mode == ClientType.CLOUD) { // Cloud subscribes to two topics
                val nackTopic = Topic("functions/$funcName/nack")
                val nackSubscribe = subscribe(nackTopic, fence)
                if (nackSubscribe == StatusCode.Success) { newTopics.add(ListeningTopic(nackTopic, fence)) }
            }
            return if (newTopics.isNotEmpty()) {
                newTopics.forEach {  listeningTopics.add(it) } // add to local registry
                logger.debug("ListeningTopics appended by ${newTopics.size}: {}", listeningTopics)
                newTopics // for error handling purposes
            } else {
                logger.debug("ListeningTopics didn't change. Nothing subscribed new!")
                mutableSetOf<ListeningTopic>()
            }
        } else
            return null // failure
    }

    // returns a map of function name to either call, result, or/and ack/nack
    fun subscribedFunctionsList(): Map<String, List<String>> {
        val functionCalls = listeningTopics.map { pair -> pair.topic.topic }//.filter { it.endsWith("/call") }
        logger.debug("functions that $id already listening to: {}", functionCalls)
        return functionCalls.map { val partialTopic = it.substringAfter("/").split("/");
        listOf(partialTopic.first(), partialTopic[1])}.groupBy { it.first() }.mapValues { it.value.map { pair -> pair[1] } }// take name of function and the action between '/', e.g. functions/"f1/call"
    }
    // returns three states: "success", null (failure), or "already exist"
    private fun subscribe(topic: Topic, fence: Geofence): StatusCode {
        if (!isSubscribedTo(topic.topic)) {
            gbSimpleClient.send(Payload.SUBSCRIBEPayload(topic, fence))
            val subAck = gbSimpleClient.receiveWithTimeout(8000)
            return if (subAck is Payload.SUBACKPayload){
                if (subAck.reasonCode == ReasonCode.GrantedQoS0){
                    logger.info("$id successfully subscribed to '${topic.topic}' in $fence. details: {}", subAck)
                    StatusCode.Success
                } else {
                    logger.error("Error Subscribing to '${topic.topic}' by $id. Reason: {}.", subAck.reasonCode)
                    StatusCode.Failure
                }
            } else {
                logger.fatal("Expected a SubAck Payload! {}", subAck)
                StatusCode.Failure
            }
        } else {
            logger.warn("already subscribed to the '${topic.topic}'")
            return  StatusCode.AlreadyExist // not a failure
        }
    }

    fun unsubscribeFunction(funcName: String) :MutableSet<Topic> {
        logger.debug("unsubscribeFunction() call. func:'{}'", funcName)
        var topicsToDelete: MutableSet<Topic> = mutableSetOf()
        var baseTopic = "functions/$funcName"
        baseTopic += when (mode) {
            ClientType.EDGE, ClientType.CLOUD   -> "/call"
            ClientType.CLIENT -> "/result"
        }
        val topic = Topic(baseTopic)
        val unSubscribe: StatusCode = unsubscribe(topic)
        if (unSubscribe == StatusCode.Success) { topicsToDelete.add(topic) }

        // unlike subscribeFunction() we won't abort if the first unsubscribe fails or doesn't exist
        if (mode == ClientType.CLIENT) { // Client subscribes to two topics
            val ackTopic = Topic("functions/$funcName/ack")
            val ackUnsubscribe = unsubscribe(ackTopic)
            if (ackUnsubscribe == StatusCode.Success) { topicsToDelete.add(ackTopic) }
        }
        if (mode == ClientType.CLOUD) { // Cloud subscribes to two topics
            val nackTopic = Topic("functions/$funcName/nack")
            val nackUnsubscribe = unsubscribe(nackTopic)
            if (nackUnsubscribe == StatusCode.Success) { topicsToDelete.add(nackTopic) }
        }
        return if (topicsToDelete.isNotEmpty()) {
            logger.debug("listeningTopic size before unsubscribing: {}", listeningTopics.size)
            topicsToDelete.forEach {  obsoleteTopic -> // remove from local registry
                val lsToDelete = listeningTopics.find { it.topic == obsoleteTopic }
                listeningTopics.remove(lsToDelete)
            }
            logger.debug("listeningTopic size after unsubscribing: {}", listeningTopics.size)
            logger.debug("ListeningTopics decreased by ${topicsToDelete.size}: {}", topicsToDelete.map { it.topic })
            topicsToDelete // for error handling purposes
        } else {
            logger.debug("ListeningTopics didn't change. Nothing unsubscribed new!")
            mutableSetOf<Topic>()
       }
    }

    // returns three states: "success", null (failure), or "not exist"
    private fun unsubscribe(topic: Topic): StatusCode {
        if (isSubscribedTo(topic.topic)) {
            gbSimpleClient.send(Payload.UNSUBSCRIBEPayload(topic))
            val unsubAck = gbSimpleClient.receiveWithTimeout(8000)
            if (unsubAck is Payload.UNSUBACKPayload) {
                if (unsubAck.reasonCode == ReasonCode.Success){
                    logger.info("GeoBroker's unSub ACK for $id:  topic: '{}'", topic.topic)
                    return StatusCode.Success
                } else {
                    logger.error("Error unSubscribing from '${topic.topic}' by $id. Reason: {}.", unsubAck.reasonCode)
                    return StatusCode.Failure
                }
            } else {
                logger.fatal("Expected an unSubAck Payload! {}", unsubAck)
                return StatusCode.Failure
            }
        } else {
            logger.warn("Subscription '${topic.topic}' doesn't exist.")
            return StatusCode.NotExist
        }
    }
    private fun isSubscribedTo(topic: String): Boolean { // NOTE: checks only the topic, not the fence
        return listeningTopics.map { pair -> pair.topic.topic }.any { it == topic }
    }

    fun listenForFunction(type: String, timeout: Int): FunctionMessage? {
        // function call/ack/nack/result
        val msg: Payload?
        val enqueuedPub = pubQueue.poll()
        if (enqueuedPub == null) {
            logger.info("Listening to the geoBroker server for a '$type'...")
            msg = when (timeout){
                0 -> gbSimpleClient.receive() // blocking
                else -> gbSimpleClient.receiveWithTimeout(timeout) // returns null after expiry
            }
            if (timeout > 0 && msg == null) {
                logger.error("Listening timeout (${timeout}ms)!")
                return null
            }
            logger.debug("EVENT from geoBroker: {}", msg)
        } else {
            msg = enqueuedPub
            logger.debug("Pub queue's size is ${pubQueue.size}. dequeued: {}", enqueuedPub)
        }

        if (msg is Payload.PUBLISHPayload) {
// wiki:    msg.topic    => Topic(topic=functions/f1/call)
// wiki:    msg.content  => message
// wiki:    msg.geofence => BUFFER (POINT (0 0), 2)
            val topic = msg.topic.topic.split("/")
            if(topic.first() == "functions") {
                val message = gson.fromJson(msg.content, FunctionMessage::class.java) // return FunctionMessage(funcName, FunctionAction.valueOf(funcAction), msg.content, Model.TypeCode.Piggy)
                return message
            } else {
                logger.error("msg is not related to the functions! {}", msg.topic.topic)
                return null
            }
        } else if(msg == null) {
            logger.error("Expected a PUBLISHPayload, but null received from geoBroker!")
            return null
        } else {
            ackQueue.add(msg)
            logger.warn("Not a PUBLISHPayload! adding it to the 'ackQueue'. dump: {}", msg)
            return null
        }
    }
    fun listenForPubAckAndProcess(funcAct: FunctionAction, funcName: String, timeout: Int): StatusCode {
        val enqueuedAck = ackQueue.poll() //FIXME: the ack could also be a Disconnect, SubAck or UnSubAck, or others
        if (enqueuedAck == null) {
            if (timeout > 0)
                logger.debug("PubAck queue is empty. Listening for a PubAck for ${timeout}ms...")
            else
                logger.debug("PubAck queue is empty. Listening for a PubAck...")
            val pubAck = gbSimpleClient.receiveWithTimeout(timeout)
            val pubStatus = processPublishAckSuccess(pubAck, funcName, funcAct, timeout > 0) // will push to the pubQueue
            return pubStatus
        } else {
            logger.debug("PubAck queue's size is ${ackQueue.size}. dequeued: {}", enqueuedAck)
            if (enqueuedAck is Payload.PUBACKPayload){
                val pubStatus = processPublishAckSuccess(enqueuedAck, funcName, funcAct, timeout > 0) // will push to the pubQueue
                return pubStatus
            } else {
                logger.fatal("Expected a PubAck in the ackQueue! Dismissed: {}", enqueuedAck)
                return StatusCode.Retry
            }
        }
    }

    fun updateLocation(newLoc :Location) :StatusCode{
        gbSimpleClient.send(Payload.PINGREQPayload(newLoc))
        val pubAck = gbSimpleClient.receiveWithTimeout(8000)
        logger.debug("ping ack: {}", pubAck) //DISCONNECTPayload(reasonCode=WrongBroker, brokerInfo=BrokerInfo(brokerId=Frankfurt, ip=localhost, port=5559)
        if(pubAck is Payload.DISCONNECTPayload) {
            logger.warn("moved outside of the current broker's area.")
            if (pubAck.reasonCode == ReasonCode.WrongBroker){ // you are now outside my area
                location = newLoc // update the local, as the current broker is no longer responsible for us
                if (pubAck.brokerInfo != null) {
                    val changeStatus = changeBroker(pubAck.brokerInfo!!)
                    if (changeStatus == StatusCode.Success) {
                        logger.info("location updated to {}", location)
                        return StatusCode.Success
                    } else {
                        logger.fatal("Failed to change the broker. And the previous broker is no longer responsible")
                        throw RuntimeException("Error updating the location to $newLoc")
                    }
                } else {
                    logger.fatal("No broker is responsible for the current location")
                    throw RuntimeException("Error updating the location to $newLoc")
                }
            } else {
                logger.fatal("unexpected reason code: {}", pubAck.reasonCode)
                throw RuntimeException("Error updating the location to $newLoc")
            }
        } else if (pubAck is Payload.PINGRESPPayload) {
            if(pubAck.reasonCode == ReasonCode.LocationUpdated) { // success
                location = newLoc
                logger.info("location updated to {}", location)
                return StatusCode.Success
            } else {
                logger.fatal("unexpected reason code: {}", pubAck.reasonCode)
                throw RuntimeException("Error updating the location to $newLoc")
            }
        } else if (pubAck == null) {
            logger.error("Updating location failed! No response from the '${gbSimpleClient.identity}' broker")
            return StatusCode.Failure
        } else {
            logger.fatal("Unexpected ack when updating the location! Received geoBroker's answer: {}", pubAck)
            throw RuntimeException("Error updating the location to $newLoc")
        }
    }

    protected fun changeBroker(broker: BrokerInfo): StatusCode {
        logger.warn("changing the remote broker to $broker...")
        val oldBroker = gbSimpleClient
        gbSimpleClient = SimpleClient(broker.ip, broker.port, identity = id)
        gbSimpleClient.send(Payload.CONNECTPayload(location)) // connect

        val connAck = gbSimpleClient.receiveWithTimeout(8000)
        val connSuccess = processConnAckSuccess(connAck, broker, true)

        if(connSuccess) {
            logger.info("switched the remote broker to: ${broker.brokerId}")
            oldBroker.tearDownClient()
            logger.info("disconnected from the previous broker")
            return StatusCode.Success
        } else {
            logger.error("failed to change the remote broker to: $broker. Thus, remote geobroker is not changed")
            gbSimpleClient = oldBroker
            return StatusCode.Failure
        }
    }

    // follow geoBroker instructions to Disconnect
    fun terminate() {
        gbSimpleClient.send(Payload.DISCONNECTPayload(ReasonCode.NormalDisconnection)) // disconnect
        gbSimpleClient.tearDownClient()
        if (processManager.tearDown(3000)) {
            logger.info("GBClient Channel shut down properly.")
        } else {
            logger.fatal("ProcessManager reported that processes are still running: {}",
                processManager.incompleteZMQProcesses)
        }
//        exitProcess(0) // terminates current process
    }


    protected fun processConnAckSuccess(connAck: Payload?, broker: BrokerInfo, withTimeout: Boolean) :Boolean{
        if (connAck is Payload.CONNACKPayload && connAck.reasonCode == ReasonCode.Success)
            return true
        else if (connAck is Payload.DISCONNECTPayload) {
            if (connAck.reasonCode == ReasonCode.ProtocolError)
                logger.fatal("${connAck.reasonCode}! duplicate client id? can't connect to the geobroker ${broker.ip}:${broker.port}.")
            else
                logger.fatal("${connAck.reasonCode}! can't connect to the geobroker ${broker.ip}:${broker.port}. other suggested server? ${connAck.brokerInfo}")

            return false
        } else if (connAck == null) {
            if (withTimeout)
                throw RuntimeException("Timeout! can't connect to the geobroker ${broker.ip}:${broker.port}. Check the Address and try again")
            else
                throw RuntimeException("Empty Response! can't connect to the geobroker ${broker.ip}:${broker.port}. Check the Address and try again")
        } else {
            logger.fatal("Unexpected 'Conn ACK'! Received geoBroker's answer: {}", connAck)
            return false
        }
    }
    protected fun processPublishAckSuccess(pubAck: Payload?, funcName: String, funcAct: FunctionAction, withTimeout: Boolean): StatusCode {
        val logMsg = "GeoBroker's 'Publish ACK' for the '$funcName' $funcAct by '$id': {}"
        if (pubAck is Payload.PUBACKPayload) {
            val noError = logPublishAck(pubAck, logMsg) // logs the reasonCode
            if (noError) return StatusCode.Success
            else logger.error("${pubAck.reasonCode}! 'Publish ACK' received for '$funcName' $funcAct by '$id'")
        } else if (pubAck == null && withTimeout) {
            logger.error("Timeout! no 'Publish ACK' received for '$funcName' $funcAct by '$id'")
        } else if (pubAck is Payload.PUBLISHPayload) {
            pubQueue.add(pubAck) // to be processed by listenForFunction()
            logger.warn("Not a PUBACKPayload! adding it to the 'pubQueue'. dump: {}", pubAck)
            return StatusCode.Retry
        } else {
            logger.error("Unexpected! $logMsg", pubAck)
        }
        return StatusCode.Failure
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