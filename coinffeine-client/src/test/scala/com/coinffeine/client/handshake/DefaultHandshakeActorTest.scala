package com.coinffeine.client.handshake

import java.math.BigInteger
import scala.concurrent.duration._

import akka.actor.Props
import akka.testkit.TestProbe
import com.google.bitcoin.core.{ECKey, Transaction, TransactionOutput}
import com.google.bitcoin.crypto.TransactionSignature
import org.scalatest.mock.MockitoSugar

import com.coinffeine.client.CoinffeineClientTest
import com.coinffeine.client.handshake.HandshakeActor.StartHandshake
import com.coinffeine.common.BitcoinAmount
import com.coinffeine.common.Exchange.BuyerRole
import com.coinffeine.common.blockchain.TransactionProcessor
import com.coinffeine.common.protocol.MockTransaction
import com.coinffeine.common.protocol.gateway.MessageGateway.ReceiveMessage
import com.coinffeine.common.protocol.messages.handshake.{RefundTxSignatureRequest, RefundTxSignatureResponse}

/** Test fixture for testing the handshake actor interaction, one derived class per scenario. */
abstract class DefaultHandshakeActorTest(systemName: String)
  extends CoinffeineClientTest(systemName) with MockitoSugar {

  // Overridable settings
  def resubmitRefundSignatureTimeout: FiniteDuration = 1.minute
  def refundSignatureAbortTimeout: FiniteDuration = 1.minute

  object MockTransactions extends TransactionProcessor {
    val buyerDeposit = MockTransaction()
    val buyerRefundTransaction = MockTransaction()
    val buyerRefundSignature = new TransactionSignature(BigInteger.ZERO, BigInteger.ZERO)
    val buyerCommitmentTransaction = MockTransaction()
    val sellerDeposit = MockTransaction()
    val sellerRefundTransaction = MockTransaction()
    val sellerRefundSignature = new TransactionSignature(BigInteger.ONE, BigInteger.ONE)
    val invalidRefundTransaction = MockTransaction()
    val sellerCommitmentTransaction = MockTransaction()

    override def createTransaction(inputs: Seq[TransactionOutput], outputs: Seq[(ECKey, BitcoinAmount)], lockTime: Option[Long]): Transaction = ???

    override def multisign(tx: Transaction, inputIndex: Int, keys: ECKey*): TransactionSignature = ???

    override def sign(tx: Transaction, inputIndex: Int, key: ECKey): TransactionSignature = ???

    override def isValidSignature(tx: Transaction, output: Int, sig: TransactionSignature): Boolean = {
      (tx, output, sig) match {
        case (`buyerRefundTransaction`, 0, `buyerRefundSignature`) => true
        case _ => false
      }
    }
  }

  val exchange = sampleExchange.copy(parameters = sampleExchange.parameters.copy(
    resubmitRefundSignatureTimeout = resubmitRefundSignatureTimeout,
    refundSignatureAbortTimeout = refundSignatureAbortTimeout
  ))
  val role = BuyerRole
  val handshake = role.startHandshake(MockTransactions, exchange, MockTransactions.buyerDeposit)
  override val counterpart = role.her(exchange).connection
  override val broker = exchange.broker.connection
  val listener = TestProbe()
  val blockchain = TestProbe()
  val actor = system.actorOf(Props(new DefaultHandshakeActor(MockTransactions)), "handshake-actor")
  listener.watch(actor)

  def givenActorIsInitialized(): Unit =
    actor ! StartHandshake(handshake, role, gateway.ref, blockchain.ref, Set(listener.ref))

  def shouldForwardRefundSignatureRequest(): Unit = {
    val refundSignatureRequest =
      RefundTxSignatureRequest(exchange.id, MockTransactions.buyerRefundTransaction)
    shouldForward (refundSignatureRequest) to counterpart
  }

  def shouldSignCounterpartRefund(): Unit = {
    val request = RefundTxSignatureRequest(exchange.id, MockTransactions.sellerRefundTransaction)
    gateway.send(actor, ReceiveMessage(request, counterpart))
    val refundSignatureRequest =
      RefundTxSignatureResponse(exchange.id, MockTransactions.sellerRefundSignature)
    shouldForward (refundSignatureRequest) to counterpart
  }
}
