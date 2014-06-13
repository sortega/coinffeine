package com.coinffeine.client.handshake

import scala.concurrent.duration._
import scala.util.Success

import com.google.bitcoin.core.Sha256Hash
import com.google.bitcoin.crypto.TransactionSignature

import com.coinffeine.client.handshake.HandshakeActor.HandshakeResult
import com.coinffeine.common.{Exchange, PeerConnection}
import com.coinffeine.common.blockchain.BlockchainActor._
import com.coinffeine.common.protocol.gateway.MessageGateway.{ReceiveMessage, Subscribe}
import com.coinffeine.common.protocol.messages.arbitration.CommitmentNotification
import com.coinffeine.common.protocol.messages.handshake._

class HappyPathDefaultHandshakeActorTest extends DefaultHandshakeActorTest("happy-path") {

  override val resubmitRefundSignatureTimeout = 1.minute
  override val refundSignatureAbortTimeout = 1.minute

  "Handshake happy path" should "subscribe to the relevant messages when initialized" in {
    gateway.expectNoMsg()
    givenActorIsInitialized()
    val Subscribe(filter) = gateway.expectMsgClass(classOf[Subscribe])
    val relevantSignatureRequest =
      RefundTxSignatureRequest(exchange.id, MockTransactions.sellerRefundTransaction)
    val irrelevantSignatureRequest =
      RefundTxSignatureRequest(Exchange.Id("other-id"), MockTransactions.sellerRefundTransaction)
    val relevantSignature =
      RefundTxSignatureResponse(exchange.id, MockTransactions.buyerRefundSignature)
    filter(fromCounterpart(relevantSignatureRequest)) should be (true)
    filter(ReceiveMessage(relevantSignatureRequest, PeerConnection("other"))) should be (false)
    filter(fromCounterpart(irrelevantSignatureRequest)) should be (false)
    filter(fromCounterpart(relevantSignature)) should be (true)
    filter(fromBroker(CommitmentNotification(exchange.id, mock[Sha256Hash], mock[Sha256Hash]))) should be (true)
    filter(fromBroker(ExchangeAborted(exchange.id, "failed"))) should be (true)
    filter(fromCounterpart(ExchangeAborted(exchange.id, "failed"))) should be (false)
    filter(fromBroker(ExchangeAborted(Exchange.Id("other-id"), "failed"))) should be (false)
  }

  it should "and requesting refund transaction signature" in {
    shouldForwardRefundSignatureRequest()
  }

  it should "reject signature of invalid counterpart refund transactions" in {
    val invalidRequest =
      RefundTxSignatureRequest(exchange.id, MockTransactions.invalidRefundTransaction)
    gateway.send(actor, fromCounterpart(invalidRequest))
    gateway.expectNoMsg(100 millis)
  }

  it should "sign counterpart refund while waiting for our refund" in {
    shouldSignCounterpartRefund()
  }

  it should "don't be fooled by invalid refund TX or source and resubmit signature request" in {
    gateway.send(actor,
      fromCounterpart(RefundTxSignatureResponse(exchange.id, mock[TransactionSignature])))
    shouldForwardRefundSignatureRequest()
  }

  it should "send commitment TX to the broker after getting his refund TX signed" in {
    gateway.send(actor,
      fromCounterpart(RefundTxSignatureResponse(exchange.id, MockTransactions.buyerRefundSignature)))
    shouldForward (ExchangeCommitment(exchange.id, MockTransactions.buyerCommitmentTransaction)) to broker
  }

  it should "sign counterpart refund after having our refund signed" in {
    shouldSignCounterpartRefund()
  }

  val publishedTransactions = Set(
    MockTransactions.buyerCommitmentTransaction.getHash,
    MockTransactions.sellerCommitmentTransaction.getHash
  )

  it should "wait until the broker publishes commitments" in {
    listener.expectNoMsg(100 millis)
    gateway.send(actor, fromBroker(CommitmentNotification(
      exchange.id,
      MockTransactions.buyerCommitmentTransaction.getHash,
      MockTransactions.sellerCommitmentTransaction.getHash
    )))
    val confirmations = exchange.parameters.commitmentConfirmations
    blockchain.expectMsgAllOf(
      NotifyWhenConfirmed(MockTransactions.buyerCommitmentTransaction.getHash, confirmations),
      NotifyWhenConfirmed(MockTransactions.sellerCommitmentTransaction.getHash, confirmations)
    )
  }

  it should "wait until commitments are confirmed" in {
    listener.expectNoMsg(100 millis)
    publishedTransactions.foreach(tx => blockchain.send(actor, TransactionConfirmed(tx, 1)))
    listener.expectMsg(HandshakeResult(Success(MockTransactions.buyerRefundSignature)))
  }

  it should "finally terminate himself" in {
    listener.expectTerminated(actor)
  }
}
