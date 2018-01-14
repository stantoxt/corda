package net.corda.attachmentdemo

import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions.Companion.invokeRpc
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.nodeapi.internal.config.User
import net.corda.testing.DUMMY_BANK_A_NAME
import net.corda.testing.DUMMY_BANK_B_NAME
import net.corda.testing.driver.PortAllocation
import net.corda.testing.driver.driver
import org.junit.Test
import java.util.concurrent.CompletableFuture.supplyAsync

class AttachmentDemoTest {
    // run with a 10,000,000 bytes in-memory zip file. In practice, a slightly bigger file will be used (~10,002,000 bytes).
    @Test
    fun `attachment demo using a 10MB zip file`() {
        val numOfExpectedBytes = 10_000_000
        driver(portAllocation = PortAllocation.Incremental(20000)) {
            val demoUser = listOf(User("demo", "demo", setOf(
                    startFlow<AttachmentDemoFlow>(),
                    invokeRpc(CordaRPCOps::attachmentExists),
                    invokeRpc(CordaRPCOps::uploadAttachment),
                    invokeRpc(CordaRPCOps::openAttachment),
                    invokeRpc(CordaRPCOps::wellKnownPartyFromX500Name),
                    invokeRpc(CordaRPCOps::internalVerifiedTransactionsFeed)
            )))
            val (nodeA, nodeB) = listOf(
                    startNode(providedName = DUMMY_BANK_A_NAME, rpcUsers = demoUser, maximumHeapSize = "1g"),
                    startNode(providedName = DUMMY_BANK_B_NAME, rpcUsers = demoUser, maximumHeapSize = "1g")
            ).map { it.getOrThrow() }
            startWebserver(nodeB).getOrThrow()

            val senderThread = supplyAsync {
                nodeA.rpcClientToNode().start(demoUser[0].username, demoUser[0].password).use {
                    sender(it.proxy, numOfExpectedBytes)
                }
            }

            val recipientThread = supplyAsync {
                nodeB.rpcClientToNode().start(demoUser[0].username, demoUser[0].password).use {
                    recipient(it.proxy, nodeB.webAddress.port)
                }
            }

            senderThread.getOrThrow()
            recipientThread.getOrThrow()
        }
    }
}
