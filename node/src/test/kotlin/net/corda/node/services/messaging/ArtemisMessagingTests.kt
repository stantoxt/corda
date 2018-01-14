package net.corda.node.services.messaging

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.context.AuthServiceId
import net.corda.core.crypto.generateKeyPair
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.internal.configureDatabase
import net.corda.node.internal.security.RPCSecurityManager
import net.corda.node.internal.security.RPCSecurityManagerImpl
import net.corda.node.services.config.CertChainPolicyConfig
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.configureWithDevSSLCertificate
import net.corda.node.services.network.NetworkMapCacheImpl
import net.corda.node.services.network.PersistentNetworkMapCache
import net.corda.node.services.transactions.PersistentUniquenessProvider
import net.corda.node.utilities.AffinityExecutor.ServiceAffinityExecutor
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.*
import net.corda.testing.internal.LogHelper
import net.corda.testing.internal.rigorousMock
import net.corda.testing.node.MockServices.Companion.MOCK_VERSION_INFO
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtemisMessagingTests {
    companion object {
        const val TOPIC = "platform.self"
    }

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val serverPort = freePort()
    private val rpcPort = freePort()
    private val identity = generateKeyPair()

    private lateinit var config: NodeConfiguration
    private lateinit var database: CordaPersistence
    private lateinit var securityManager: RPCSecurityManager
    private var messagingClient: P2PMessagingClient? = null
    private var messagingServer: ArtemisMessagingServer? = null

    private lateinit var networkMapCache: NetworkMapCacheImpl

    @Before
    fun setUp() {
        securityManager = RPCSecurityManagerImpl.fromUserList(users = emptyList(), id = AuthServiceId("TEST"))
        abstract class AbstractNodeConfiguration : NodeConfiguration
        config = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath()).whenever(it).baseDirectory
            doReturn(ALICE_NAME).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
            doReturn("").whenever(it).exportJMXto
            doReturn(emptyList<CertChainPolicyConfig>()).whenever(it).certificateChainCheckPolicies
            doReturn(5).whenever(it).messageRedeliveryDelaySeconds
            doReturn(true).whenever(it).useAMQPBridges
        }
        LogHelper.setLevel(PersistentUniquenessProvider::class)
        database = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(), rigorousMock())
        networkMapCache = NetworkMapCacheImpl(PersistentNetworkMapCache(database, emptyList(), ALICE_NAME), rigorousMock())
    }

    @After
    fun cleanUp() {
        messagingClient?.stop()
        messagingServer?.stop()
        database.close()
        LogHelper.reset(PersistentUniquenessProvider::class)
    }

    @Test
    fun `server starting with the port already bound should throw`() {
        ServerSocket(serverPort).use {
            val messagingServer = createMessagingServer()
            assertThatThrownBy { messagingServer.start() }
        }
    }

    @Test
    fun `client should connect to remote server`() {
        val remoteServerAddress = freeLocalHostAndPort()

        createMessagingServer(remoteServerAddress.port).start()
        createMessagingClient(server = remoteServerAddress)
        startNodeMessagingClient()
    }

    @Test
    fun `client should throw if remote server not found`() {
        val serverAddress = freeLocalHostAndPort()
        val invalidServerAddress = freeLocalHostAndPort()

        createMessagingServer(serverAddress.port).start()

        messagingClient = createMessagingClient(server = invalidServerAddress)
        assertThatThrownBy { startNodeMessagingClient() }
        messagingClient = null
    }

    @Test
    fun `client should connect to local server`() {
        createMessagingServer().start()
        createMessagingClient()
        startNodeMessagingClient()
    }

    @Test
    fun `client should be able to send message to itself`() {
        val (messagingClient, receivedMessages) = createAndStartClientAndServer()
        val message = messagingClient.createMessage(TOPIC, data = "first msg".toByteArray())
        messagingClient.send(message, messagingClient.myAddress)

        val actual: Message = receivedMessages.take()
        assertEquals("first msg", String(actual.data))
        assertNull(receivedMessages.poll(200, MILLISECONDS))
    }

    @Test
    fun `platform version is included in the message`() {
        val (messagingClient, receivedMessages) = createAndStartClientAndServer(platformVersion = 3)
        val message = messagingClient.createMessage(TOPIC, data = "first msg".toByteArray())
        messagingClient.send(message, messagingClient.myAddress)

        val received = receivedMessages.take()
        assertThat(received.platformVersion).isEqualTo(3)
    }

    private fun startNodeMessagingClient() {
        messagingClient!!.start()
    }

    private fun createAndStartClientAndServer(platformVersion: Int = 1): Pair<P2PMessagingClient, BlockingQueue<ReceivedMessage>> {
        val receivedMessages = LinkedBlockingQueue<ReceivedMessage>()

        createMessagingServer().start()

        val messagingClient = createMessagingClient(platformVersion = platformVersion)
        startNodeMessagingClient()
        messagingClient.addMessageHandler(TOPIC) { message, _ ->
            receivedMessages.add(message)
        }
        // Run after the handlers are added, otherwise (some of) the messages get delivered and discarded / dead-lettered.
        thread(isDaemon = true) { messagingClient.run() }

        return Pair(messagingClient, receivedMessages)
    }

    private fun createMessagingClient(server: NetworkHostAndPort = NetworkHostAndPort("localhost", serverPort), platformVersion: Int = 1, maxMessageSize: Int = MAX_MESSAGE_SIZE): P2PMessagingClient {
        return database.transaction {
            P2PMessagingClient(
                    config,
                    MOCK_VERSION_INFO.copy(platformVersion = platformVersion),
                    server,
                    identity.public,
                    ServiceAffinityExecutor("ArtemisMessagingTests", 1),
                    database,
                    maxMessageSize = maxMessageSize).apply {
                config.configureWithDevSSLCertificate()
                messagingClient = this
            }
        }
    }

    private fun createMessagingServer(local: Int = serverPort, rpc: Int = rpcPort, maxMessageSize: Int = MAX_MESSAGE_SIZE): ArtemisMessagingServer {
        return ArtemisMessagingServer(config, local, rpc, networkMapCache, securityManager, maxMessageSize).apply {
            config.configureWithDevSSLCertificate()
            messagingServer = this
        }
    }
}
