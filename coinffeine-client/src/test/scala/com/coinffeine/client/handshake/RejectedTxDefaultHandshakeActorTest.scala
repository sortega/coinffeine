package com.coinffeine.client.handshake

import scala.concurrent.duration._

import com.coinffeine.client.handshake.HandshakeActor._
import com.coinffeine.common.blockchain.BlockchainActor.TransactionRejected
import com.coinffeine.common.protocol.messages.arbitration.CommitmentNotification
import com.coinffeine.common.protocol.messages.handshake.RefundTxSignatureResponse

class RejectedTxDefaultHandshakeActorTest extends DefaultHandshakeActorTest("rejected-tx") {

  override val resubmitRefundSignatureTimeout = 10.seconds
  override val refundSignatureAbortTimeout = 100.millis

  "Handshakes in which TX are rejected" should "have a failed handshake result" in {
    givenActorIsInitialized()
    gateway.send(actor, fromCounterpart(RefundTxSignatureResponse(exchange.id, MockTransactions.buyerRefundSignature)))
    gateway.send(actor, fromBroker(CommitmentNotification(
      exchange.id,
      MockTransactions.buyerCommitmentTransaction.getHash,
      MockTransactions.sellerCommitmentTransaction.getHash
    )))
    blockchain.send(actor, TransactionRejected(MockTransactions.sellerCommitmentTransaction.getHash))

    val result = listener.expectMsgClass(classOf[HandshakeResult]).refundSig
    result should be ('failure)
    result.toString should include (
      s"transaction ${MockTransactions.sellerCommitmentTransaction.getHash} (counterpart) was rejected")
  }

  it should "terminate" in {
    listener.expectTerminated(actor)
  }
}
