package com.coinffeine.client.handshake

import scala.util.{Failure, Success, Try}

import akka.actor._
import com.google.bitcoin.core.Sha256Hash
import com.google.bitcoin.crypto.TransactionSignature

import com.coinffeine.common
import com.coinffeine.client.MessageForwarding
import com.coinffeine.client.handshake.DefaultHandshakeActor._
import com.coinffeine.client.handshake.HandshakeActor._
import com.coinffeine.common.FiatCurrency
import com.coinffeine.common.blockchain.TransactionProcessor
import com.coinffeine.common.blockchain.BlockchainActor._
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.gateway.MessageGateway._
import com.coinffeine.common.protocol.messages.arbitration.CommitmentNotification
import com.coinffeine.common.protocol.messages.handshake._

private[handshake] class DefaultHandshakeActor[C <: FiatCurrency](
    handshake: common.Exchange.Handshake[C],
    processor: TransactionProcessor)
  extends Actor with ActorLogging {

  import context.dispatcher

  private var timers = Seq.empty[Cancellable]

  override def postStop(): Unit = timers.foreach(_.cancel())

  override def receive = {
    case StartHandshake(messageGateway, blockchain, resultListeners) =>
      new InitializedHandshake(messageGateway, blockchain, resultListeners).startHandshake()
  }

  private class InitializedHandshake(messageGateway: ActorRef,
                                     blockchain: ActorRef,
                                     resultListeners: Set[ActorRef]) {

    private val exchange = handshake.exchange
    private val forwarding = new MessageForwarding(
      messageGateway, exchange.her.connection, exchange.broker.connection)

    def startHandshake(): Unit = {
      subscribeToMessages()
      requestRefundSignature()
      scheduleTimeouts()
      log.info("Handshake {}: Handshake started", exchange.id)
      context.become(waitForRefundSignature)
    }

    private val signCounterpartRefund: Receive = {
      case ReceiveMessage(RefundTxSignatureRequest(_, refundTransaction), _) =>
        handshake.signHerRefund(processor, refundTransaction) match {
          case Success(refundSignature) =>
            forwarding.forwardToCounterpart(RefundTxSignatureResponse(exchange.id, refundSignature))
            log.info("Handshake {}: Signing refund TX {}", exchange.id,
              refundTransaction.getHashAsString)
          case Failure(cause) =>
            log.warning("Handshake {}: Dropping invalid refund: {}", exchange.id, cause)
            // TODO: what's next?
        }
    }

    private val receiveSignedRefund: Receive = {
      case ReceiveMessage(RefundTxSignatureResponse(_, refundSignature), _) =>
        if (handshake.validateHerRefundSignature(processor, refundSignature)) {
          forwarding.forwardToBroker(
            ExchangeCommitment(exchange.id, handshake.myDeposit))
          log.info("Handshake {}: Got a valid refund TX signature", exchange.id)
          context.become(waitForPublication(refundSignature))
        } else {
          requestRefundSignature()
          log.warning("Handshake {}: Rejecting invalid refund signature {}",
            exchange.id, refundSignature)
        }

      case ResubmitRequestSignature =>
        requestRefundSignature()
        log.info("Handshake {}: Re-requesting refund signature: {}", exchange.id)

      case RequestSignatureTimeout =>
        val cause = RefundSignatureTimeoutException(exchange.id)
        forwarding.forwardToBroker(ExchangeRejection(exchange.id, cause.toString))
        finishWithResult(Failure(cause))
    }

    private def getNotifiedByBroker(refundSig: TransactionSignature): Receive = {
      case ReceiveMessage(CommitmentNotification(_, buyerTx, sellerTx), _) =>
        val transactions = Set(buyerTx, sellerTx)
        transactions.foreach { tx =>
          blockchain ! NotifyWhenConfirmed(tx, exchange.parameters.commitmentConfirmations)
        }
        log.info("Handshake {}: The broker published {} and {}, waiting for confirmations",
          exchange.id, buyerTx, sellerTx)
        context.become(waitForConfirmations(transactions, refundSig))
    }

    private val abortOnBrokerNotification: Receive = {
      case ReceiveMessage(ExchangeAborted(_, reason), _) =>
        log.info("Handshake {}: Aborted by the broker: {}", exchange.id, reason)
        finishWithResult(Failure(HandshakeAbortedException(exchange.id, reason)))
    }

    private val waitForRefundSignature =
      receiveSignedRefund orElse signCounterpartRefund orElse abortOnBrokerNotification

    private def waitForPublication(refundSig: TransactionSignature) =
      getNotifiedByBroker(refundSig) orElse signCounterpartRefund orElse abortOnBrokerNotification

    private def waitForConfirmations(
        pendingConfirmation: Set[Sha256Hash], refundSig: TransactionSignature): Receive = {

      case TransactionConfirmed(tx, confirmations)
          if confirmations >= exchange.parameters.commitmentConfirmations =>
        val stillPending = pendingConfirmation - tx
        if (!stillPending.isEmpty) {
          context.become(waitForConfirmations(stillPending, refundSig))
        } else {
          finishWithResult(Success(refundSig))
        }

      case TransactionRejected(tx) =>
        val isOwn = tx == handshake.myDeposit.getHash
        val cause = CommitmentTransactionRejectedException(exchange.id, tx, isOwn)
        log.error("Handshake {}: {}", exchange.id, cause.getMessage)
        finishWithResult(Failure(cause))
    }

    private def subscribeToMessages(): Unit = {
      val id = exchange.id
      val broker = exchange.broker
      val counterpart = exchange.her.connection
      messageGateway ! Subscribe {
        case ReceiveMessage(RefundTxSignatureRequest(`id`, _), `counterpart`) => true
        case ReceiveMessage(RefundTxSignatureResponse(`id`, _), `counterpart`) => true
        case ReceiveMessage(CommitmentNotification(`id`, _, _), `broker`) => true
        case ReceiveMessage(ExchangeAborted(`id`, _), `broker`) => true
        case _ => false
      }
    }

    private def scheduleTimeouts(): Unit = {
      timers = Seq(
        context.system.scheduler.schedule(
          initialDelay = exchange.parameters.resubmitRefundSignatureTimeout,
          interval = exchange.parameters.resubmitRefundSignatureTimeout,
          receiver = self,
          message = ResubmitRequestSignature
        ),
        context.system.scheduler.scheduleOnce(
          delay = exchange.parameters.refundSignatureAbortTimeout,
          receiver = self,
          message = RequestSignatureTimeout
        )
      )
    }

    private def requestRefundSignature(): Unit = {
      forwarding.forwardToCounterpart(
        RefundTxSignatureRequest(exchange.id, handshake.myRefund))
    }

    private def finishWithResult(result: Try[TransactionSignature]): Unit = {
      log.info("Handshake {}: handshake finished with result {}", exchange.id, result)
      resultListeners.foreach(_ ! HandshakeResult(result))
      self ! PoisonPill
    }
  }
}

object DefaultHandshakeActor {
  trait Component extends HandshakeActor.Component { this: ProtocolConstants.Component =>
    override def handshakeActorProps[C <: FiatCurrency](
        handshake: common.Exchange.Handshake[C], processor: TransactionProcessor): Props =
      Props(new DefaultHandshakeActor(handshake, processor))
  }

  /** Internal message to remind about resubmitting refund signature requests. */
  private case object ResubmitRequestSignature
  /** Internal message that aborts the handshake. */
  private case object RequestSignatureTimeout
}
