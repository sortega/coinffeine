package com.coinffeine.client.handshake

import com.coinffeine.client.exchange.UserRole

import scala.util.Try

import akka.actor.{ActorRef, Props}
import com.google.bitcoin.core.Sha256Hash
import com.google.bitcoin.crypto.TransactionSignature

import com.coinffeine.common.{Exchange, FiatCurrency}
import com.coinffeine.common
import com.coinffeine.common.blockchain.TransactionProcessor

/** A handshake actor is in charge of entering into a value exchange by getting a refundSignature
  * transaction signed and relying on the broker to publish the commitment TX.
  */
object HandshakeActor {

  /** Sent to the actor to start the handshake
    *
    * @constructor
    * @param handshake        Handshake to take part on
    * @param role             Role played on the handshake
    * @param messageGateway   Communications gateway
    * @param blockchain       Actor to ask for TX confirmations for
    * @param resultListeners  Actors to be notified of the handshake result
    */
  case class StartHandshake(
      handshake: Exchange.Handshake[_ <: FiatCurrency],
      role: Exchange.Role,
      messageGateway: ActorRef,
      blockchain: ActorRef,
      resultListeners: Set[ActorRef])

  /** Sent to the handshake listeners to notify success with a refundSignature transaction or
    * failure with an exception.
    */
  case class HandshakeResult(refundSig: Try[TransactionSignature])

  case class RefundSignatureTimeoutException(exchangeId: Exchange.Id) extends RuntimeException(
    s"Timeout waiting for a valid signature of the refund transaction of handshake $exchangeId")

  case class CommitmentTransactionRejectedException(
       exchangeId: Exchange.Id, rejectedTx: Sha256Hash, isOwn: Boolean) extends RuntimeException(
    s"Commitment transaction $rejectedTx (${if (isOwn) "ours" else "counterpart"}) was rejected"
  )

  case class HandshakeAbortedException(exchangeId: Exchange.Id, reason: String) extends RuntimeException(
    s"Handshake $exchangeId aborted externally: $reason"
  )

  trait Component {
    /** Create the properties of a handshake actor. */
    def handshakeActorProps: Props
  }
}
