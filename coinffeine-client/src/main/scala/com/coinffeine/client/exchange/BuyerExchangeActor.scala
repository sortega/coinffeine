package com.coinffeine.client.exchange

import akka.actor._

import com.coinffeine.common
import com.coinffeine.client.MessageForwarding
import com.coinffeine.client.exchange.ExchangeActor.{ExchangeSuccess, StartExchange}
import com.coinffeine.common.FiatCurrency
import com.coinffeine.common.Exchange.MicroPaymentChannel
import com.coinffeine.common.blockchain.TransactionProcessor
import com.coinffeine.common.paymentprocessor.PaymentProcessor
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.gateway.MessageGateway.{ReceiveMessage, Subscribe}
import com.coinffeine.common.protocol.messages.exchange.{PaymentProof, StepSignatures}

/** This actor implements the buyer's side of the exchange. You can find more information about
  * the algorithm at https://github.com/Coinffeine/coinffeine/wiki/Exchange-algorithm
  */
class BuyerExchangeActor(
    transactionProcessor: TransactionProcessor,
    paymentProcessor: PaymentProcessor) extends Actor with ActorLogging  {

  override def receive: Receive = {
    case StartExchange(channel, messageGateway, resultListeners) =>
      new InitializedBuyerExchange(channel, messageGateway, resultListeners).startExchange()
  }

  private class InitializedBuyerExchange[C <: FiatCurrency](
      initialChannel: MicroPaymentChannel[C],
      messageGateway: ActorRef,
      listeners: Set[ActorRef]) {

    private val exchange = initialChannel.exchange

    private val forwarding = new MessageForwarding(
      messageGateway, exchange.seller.connection, exchange.broker.connection)

    def startExchange(): Unit = {
      subscribeToMessages()
      context.become(waitForNextStepSignature(initialChannel))
      log.info(s"Exchange ${exchange.id}: Exchange started")
    }

    private def subscribeToMessages(): Unit = messageGateway ! Subscribe {
      case ReceiveMessage(StepSignatures(exchange.`id`, _, _), exchange.`seller`.connection) => true
      case _ => false
    }

    private def waitForNextStepSignature(channel: MicroPaymentChannel[C]): Receive = {
      case ReceiveMessage(StepSignatures(_, signature0, signature1), _) =>
        val signatures = (signature0, signature1)
        if (!channel.validateCurrentTransactionSignatures(transactionProcessor, signatures))
        {
          import context.dispatcher
          val paymentProof = for {
            payment <- paymentProcessor.sendPayment(
              receiverId = exchange.seller.paymentProcessorAccount,
              amount = channel.exchange.amounts.stepFiatAmount,
              comment = s"Payment for ${exchange.id}, step ${channel.currentStep}")
          } yield PaymentProof(exchange.id, payment.id)

          forwarding.forwardToCounterpart(paymentProof)
          context.become(nextWait(channel))
        } else {
          log.warning(s"Received invalid signatures $signatures in ${channel.currentStep}")
          // TODO: report the error to the counterpart and recover from this error
        }
    }

    private def nextWait(channel: MicroPaymentChannel[C]): Receive =
      if (channel.isLastStep) waitForFinalSignature(channel.close())
      else waitForNextStepSignature(channel.nextStep)

    private def waitForFinalSignature(closing: common.Exchange.Closing[C]): Receive = {
      case ReceiveMessage(StepSignatures(_, signature0, signature1), _) =>
        val signatures = (signature0, signature1)
        if (!closing.validateClosingTransactionSignatures(transactionProcessor, signatures)) {
          log.info(s"Exchange ${exchange.id}: exchange finished with success")
          // TODO: Publish transaction to blockchain
          listeners.foreach { _ ! ExchangeSuccess }
          context.stop(self)
        } else {
          log.warning(s"Received invalid final signature: $signatures")
        }
    }

  }
}

object BuyerExchangeActor {
  trait Component { this: ProtocolConstants.Component =>
    def exchangeActorProps[C <: FiatCurrency](transactionProcessor: TransactionProcessor,
                                              paymentProcessor: PaymentProcessor): Props =
      Props(new BuyerExchangeActor(transactionProcessor, paymentProcessor))
  }
}
