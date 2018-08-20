package de.hasenburg.geofencebroker.main;

import de.hasenburg.geofencebroker.communication.*;
import de.hasenburg.geofencebroker.model.InternalClientMessage;
import de.hasenburg.geofencebroker.model.Location;
import de.hasenburg.geofencebroker.model.Topic;
import de.hasenburg.geofencebroker.model.connections.ConnectionManager;
import de.hasenburg.geofencebroker.model.exceptions.CommunicatorException;
import de.hasenburg.geofencebroker.model.geofence.Geofence;
import de.hasenburg.geofencebroker.model.payload.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class LoadTest {

	private static final Logger logger = LogManager.getLogger();

	ZMQProcessManager processManager;
	ConnectionManager connectionManager;

	public static void main (String[] args) throws Exception {
	    LoadTest loadTest = new LoadTest();
	    loadTest.setUp();
	    loadTest.loadTestOwnTopic();
	    loadTest.tearDown();
	}

	public void setUp() throws Exception {
		logger.info("Running setUp");
		ConnectionManager connectionManager = new ConnectionManager();

		processManager = new ZMQProcessManager();
		processManager.runZMQProcess_Broker("tcp://localhost", 5559, "broker");
		processManager.runZMQProcess_MessageProcessor("message_processor", connectionManager);
	}

	public void tearDown() throws Exception {
		logger.info("Running tearDown.");
		processManager.tearDown(5000);
	}

	public void loadTestOwnTopic() throws InterruptedException, CommunicatorException {
		logger.info("RUNNING testOneLocations");

		List<Thread> clients = new ArrayList<>();
		int numberOfClients = 10;

		Location location = Location.random();
		Geofence geofence = new Geofence(location, 0.0); // does not matter as topics are different

		// create clients
		for (int i = 0; i < numberOfClients; i++) {
			Thread client = new Thread(new SubscribeOwnTopicProcess("tcp://localhost", 5559, 1000));
			client.start();
			clients.add(client);
		}

		// wait for clients
		for (Thread client : clients) {
			client.join();
		}

		// wait for receiving to stop
		logger.info("FINISHED");
	}

	class SubscribeOwnTopicProcess implements Runnable {

		SimpleClient simpleClient;
		int plannedMessageRounds;
		int actualMessageRounds = 1;
		Location location = Location.random();

		public SubscribeOwnTopicProcess(String address, int port, int messagesToSend) throws CommunicatorException {
			this.simpleClient = new SimpleClient(null, address, port);
			this.plannedMessageRounds = messagesToSend;
			simpleClient.sendDealerMessage(new InternalClientMessage(ControlPacketType.CONNECT, new CONNECTPayload()));
			simpleClient.sendDealerMessage(new InternalClientMessage(ControlPacketType.SUBSCRIBE,
					new SUBSCRIBEPayload(new Topic(simpleClient.getIdentity()), new Geofence(location, 0.0))));
		}

		@Override
		public void run() {
			long start = System.currentTimeMillis();
			while (actualMessageRounds <= plannedMessageRounds) {
				simpleClient.sendDealerMessage(new InternalClientMessage(ControlPacketType.PINGREQ, new PINGREQPayload(location)));
				simpleClient.sendDealerMessage(
						new InternalClientMessage(ControlPacketType.PUBLISH,
						new PUBLISHPayload(
								new Topic(simpleClient.getIdentity()),
								new Geofence(location, 0.0),
								"Some Test content that is being published.")));
				actualMessageRounds++;
			}

			try {
				simpleClient.startReceiving();
			} catch (CommunicatorException e) {
				e.printStackTrace();
			}

			int messages = plannedMessageRounds*3 + 2;
			while (simpleClient.blockingQueue.size() < messages) {
				Utility.sleepNoLog(1, 0);
			}

			logger.info("Client {} finished {} message rounds in {} milliseconds",
					simpleClient.getIdentity(), plannedMessageRounds, System.currentTimeMillis() - start);
			//let's evaluate the received messages
			HashMap<ControlPacketType, Integer> numbers = new HashMap<>();

			while (true) {
				Optional<InternalClientMessage> nextDealerMessage = simpleClient.getNextDealerMessage();
				if (!nextDealerMessage.isPresent()) {
					break;
				}
				Integer amount = numbers.get(nextDealerMessage.get().getControlPacketType());
				if (amount == null) {
					amount = 0;
				}
				amount++;
				numbers.put(nextDealerMessage.get().getControlPacketType(), amount);
			}

			logger.info("Results {}: {}", simpleClient.getIdentity(), numbers);
			simpleClient.tearDown();
		}
	}

}
