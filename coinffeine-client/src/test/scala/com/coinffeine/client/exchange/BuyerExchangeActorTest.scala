package com.coinffeine.client.exchange

import scala.concurrent.duration._

import akka.actor.Props
import akka.testkit.TestProbe
import com.google.bitcoin.crypto.TransactionSignature
import org.scalatest.mock.MockitoSugar

import com.coinffeine.client.CoinffeineClientTest
import com.coinffeine.client.exchange.ExchangeActor.{ExchangeSuccess, StartExchange}
import com.coinffeine.client.paymentprocessor.MockPaymentProcessorFactory
import com.coinffeine.common.{Exchange, PeerConnection}
import com.coinffeine.common.Currency.Euro
import com.coinffeine.common.Currency.Implicits._
import com.coinffeine.common.Exchange.BuyerRole
import com.coinffeine.common.blockchain.DefaultTransactionProcessor
import com.coinffeine.common.protocol.{MockTransaction, ProtocolConstants}
import com.coinffeine.common.protocol.gateway.MessageGateway.{ReceiveMessage, Subscribe}
import com.coinffeine.common.protocol.messages.brokerage.{Market, OrderSet}
import com.coinffeine.common.protocol.messages.exchange.{PaymentProof, StepSignatures}

class BuyerExchangeActorTest extends CoinffeineClientTest("buyerExchange") with MockitoSugar {
  val listener = TestProbe()
  val protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitRefundSignatureTimeout = 1 second,
    refundSignatureAbortTimeout = 1 minute)
  val exchange = sampleExchange
  //  new MockExchange(exchangeInfo) with BuyerUser[Currency.Euro.type]
  override val broker: PeerConnection = exchange.broker.connection
  override val counterpart: PeerConnection = exchange.seller.connection
  val transactionProcessor = new DefaultTransactionProcessor
  val paymentProcessor = new MockPaymentProcessorFactory()
    .newProcessor("account0001", initialBalance = Seq(100.EUR))
  val actor = system.actorOf(
    Props(new BuyerExchangeActor(transactionProcessor, paymentProcessor)),
    "buyer-exchange-actor"
  )
  val dummySig = TransactionSignature.dummy
  listener.watch(actor)

  val handshake = BuyerRole.startHandshake(transactionProcessor, exchange, MockTransaction())
  val initialChannel = BuyerRole.startExchange(handshake, MockTransaction())

  "The buyer exchange actor" should "subscribe to the relevant messages when initialized" in {
    gateway.expectNoMsg()
    actor ! StartExchange(initialChannel, gateway.ref, Set(listener.ref))
    val Subscribe(filter) = gateway.expectMsgClass(classOf[Subscribe])
    val relevantOfferAccepted = StepSignatures(exchange.id, dummySig, dummySig)
    val irrelevantOfferAccepted = StepSignatures(Exchange.Id("another-id"), dummySig, dummySig)
    val anotherPeer = PeerConnection("some-random-peer")
    filter(fromCounterpart(relevantOfferAccepted)) should be (true)
    filter(ReceiveMessage(relevantOfferAccepted, anotherPeer)) should be (false)
    filter(fromCounterpart(irrelevantOfferAccepted)) should be (false)
    val randomMessage = OrderSet.empty(Market(Euro))
    filter(ReceiveMessage(randomMessage, counterpart)) should be (false)
  }

  it should "respond to step signature messages by sending a payment until all " +
    "steps have are done" in {
      for (i <- 1 to exchange.amounts.totalSteps.value) {
        actor ! fromCounterpart(StepSignatures(exchange.id, dummySig -> dummySig))
        val paymentMsg = PaymentProof(exchange.id, "paymentId")
        shouldForward(paymentMsg) to counterpart
        gateway.expectNoMsg(100.milliseconds)
      }
    }

  it should "send a notification to the listeners once the exchange has finished" in {
    actor ! fromCounterpart(StepSignatures(exchange.id, dummySig, dummySig))
    listener.expectMsg(ExchangeSuccess)
  }

  it should "have transferred "
}
