package com.coinffeine.client.handshake

import scala.concurrent.duration._

import com.coinffeine.common.protocol.gateway.MessageGateway.ForwardMessage
import com.coinffeine.common.protocol.messages.handshake.ExchangeRejection

class RefundUnsignedDefaultHandshakeActorTest
  extends DefaultHandshakeActorTest("signature-timeout") {

  import com.coinffeine.client.handshake.HandshakeActor._

  override val resubmitRefundSignatureTimeout = 10.seconds
  override val refundSignatureAbortTimeout = 100.millis

  "Handshakes without our refund signed" should "be aborted after a timeout" in {
    givenActorIsInitialized()
    val result = listener.expectMsgClass(classOf[HandshakeResult]).refundSig
    result should be ('failure)
    listener.expectTerminated(actor)
  }

  it must "notify the broker that the exchange is rejected" in {
    gateway.fishForMessage() {
      case ForwardMessage(ExchangeRejection(exchange.id, _), `broker`) => true
      case _ => false
    }
  }
}
