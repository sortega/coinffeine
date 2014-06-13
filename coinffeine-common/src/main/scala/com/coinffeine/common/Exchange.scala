package com.coinffeine.common

import scala.concurrent.duration.FiniteDuration
import scala.util.{Random, Try}

import com.google.bitcoin.core.{TransactionOutput, ECKey, NetworkParameters, Transaction}
import com.google.bitcoin.crypto.TransactionSignature

import com.coinffeine.common.blockchain.TransactionProcessor
import com.coinffeine.common.paymentprocessor.PaymentProcessor

case class Exchange[C <: FiatCurrency](
  id: Exchange.Id,
  parameters: Exchange.Parameters,
  buyer: Exchange.PeerInfo,
  seller: Exchange.PeerInfo,
  broker: Exchange.BrokerInfo,
  amounts: Exchange.Amounts[C]) {
}

object Exchange {

  case class Parameters(
    lockTime: Long,
    commitmentConfirmations: Int,
    resubmitRefundSignatureTimeout: FiniteDuration,
    refundSignatureAbortTimeout: FiniteDuration)

  case class Id(value: String) {
    override def toString = s"exchange:$value"
  }

  object Id {
    def random() = new Id(value = Random.nextString(12)) // TODO: use a crypto secure method
  }

  case class StepNumber(value: Int) {
    require(value >= 0, s"Step number must be positive or zero ($value given)")

    val count = value + 1

    override def toString = s"step number $value"

    def next: StepNumber = new StepNumber(value + 1)
  }

  object StepNumber {
    val First = new StepNumber(0)
  }

  case class TotalSteps(value: Int) {
    require(value >= 0, s"Total steps must be positive or zero ($value given)")

    val isPositive: Boolean = value > 0
    val lastStep: StepNumber = StepNumber(value - 1)

    override def toString = s"$value total steps"
    def toBigDecimal = BigDecimal(value)
    def pendingStepsAfter(step: StepNumber): TotalSteps = new TotalSteps(lastStep.value - step.value)
    def isLastStep(step: StepNumber): Boolean = step == lastStep
  }

  case class BrokerInfo(connection: PeerConnection)

  case class PeerInfo(
    connection: PeerConnection,
    paymentProcessorAccount: PaymentProcessor#AccountId,
    bitcoinKey: ECKey)

  case class Amounts[C <: FiatCurrency](bitcoinNetwork: NetworkParameters,
                                        bitcoinAmount: BitcoinAmount,
                                        fiatAmount: CurrencyAmount[C],
                                        totalSteps: TotalSteps) {
    require(totalSteps.isPositive,
      s"exchange amounts must have positive total steps ($totalSteps given)")
    require(bitcoinAmount.isPositive,
      s"bitcoin amount must be positive ($bitcoinAmount given)")
    require(fiatAmount.isPositive,
      s"fiat amount must be positive ($fiatAmount given)")

    val stepBitcoinAmount: BitcoinAmount = bitcoinAmount / totalSteps.toBigDecimal
    val stepFiatAmount: CurrencyAmount[C] = fiatAmount / totalSteps.toBigDecimal
    val buyerDeposit: BitcoinAmount = stepBitcoinAmount * BigDecimal(2)
    val sellerDeposit: BitcoinAmount = stepBitcoinAmount
    val buyerInitialBitcoinAmount: BitcoinAmount = buyerDeposit
    val sellerInitialBitcoinAmount: BitcoinAmount = bitcoinAmount + sellerDeposit

    def buyerFundsAfter(step: StepNumber): (BitcoinAmount, CurrencyAmount[C]) = (
      stepBitcoinAmount * step.count,
      fiatAmount - (stepFiatAmount * step.count)
    )

    def sellerFundsAfter(step: StepNumber): (BitcoinAmount, CurrencyAmount[C]) = (
      bitcoinAmount - (stepBitcoinAmount * step.count),
      stepFiatAmount * step.count
    )
  }


  trait Role {
    def me(exchange: Exchange[_ <: FiatCurrency]): PeerInfo
    def her(exchange: Exchange[_ <: FiatCurrency]): PeerInfo
    def myDepositAmount(amounts: Amounts[_ <: FiatCurrency]): BitcoinAmount

    def signHerRefund(
        exchange: Exchange[_ <: FiatCurrency],
        processor: TransactionProcessor,
        tx: Transaction): Try[TransactionSignature] = {
      // TODO: check that refund TX is valid
      Try(processor.sign(tx, 0, me(exchange).bitcoinKey))
    }

    def startHandshake[C <: FiatCurrency](
        processor: TransactionProcessor,
        exchange: Exchange[C],
        myDeposit: Transaction): Handshake[C] = Handshake(
      exchange,
      myDeposit,
      myRefund = processor.createTransaction(
        inputs = Seq(myDeposit.getOutput(0)),
        outputs = Seq(me(exchange).bitcoinKey -> myDepositAmount(exchange.amounts))
      ),
      herRefund = None
    )

    def startExchange[C <: FiatCurrency](handshake: Handshake[C],
                                         herDeposit: Transaction): MicroPaymentChannel[C] = {
      require(handshake.isCompleted,
        "all deposit and backup transactions must be defined before starting exchange")
      val deposits = Deposits(handshake.myDeposit.getOutput(0), herDeposit.getOutput(0))
      MicroPaymentChannel[C](handshake.exchange, deposits)
    }
  }

  object BuyerRole extends Role {

    override def toString = "buyer"

    override def me(exchange: Exchange[_ <: FiatCurrency]): PeerInfo = exchange.buyer

    override def her(exchange: Exchange[_ <: FiatCurrency]): PeerInfo = exchange.seller

    override def myDepositAmount(amounts: Amounts[_ <: FiatCurrency]): BitcoinAmount =
      amounts.buyerDeposit
  }

  object SellerRole extends Role {

    override def toString = "seller"

    override def me(exchange: Exchange[_ <: FiatCurrency]): PeerInfo = exchange.seller

    override def her(exchange: Exchange[_ <: FiatCurrency]): PeerInfo = exchange.buyer

    override def myDepositAmount(amounts: Amounts[_ <: FiatCurrency]): BitcoinAmount =
      amounts.sellerDeposit
  }

  case class Handshake[C <: FiatCurrency](
      exchange: Exchange[C],
      myDeposit: Transaction,
      myRefund: Transaction,
      herRefund: Option[Transaction]) {

    val isCompleted = herRefund.isDefined

    def validateHerSignatureOfMyRefund(processor: TransactionProcessor,
                                       signature: TransactionSignature): Boolean = {
      processor.isValidSignature(myRefund, 0, signature)
    }
  }

  case class Deposits(buyerOutput: TransactionOutput, sellerOutput: TransactionOutput) {
    def toSeq = Seq(buyerOutput, sellerOutput)
  }

  case class MicroPaymentChannel[C <: FiatCurrency](
    exchange: Exchange[C],
    deposits: Deposits,
    currentStep: StepNumber = StepNumber.First) {

    val buyerFundsAfterCurrentStep = exchange.amounts.buyerFundsAfter(currentStep)
    val sellerFundsAfterCurrentStep = exchange.amounts.sellerFundsAfter(currentStep)
    val isLastStep = exchange.amounts.totalSteps.isLastStep(currentStep)

    def getCurrentTransaction(processor: TransactionProcessor): Transaction =
      processor.createTransaction(
        inputs = deposits.toSeq,
        outputs = Seq(
          exchange.buyer.bitcoinKey -> buyerFundsAfterCurrentStep._1,
          exchange.seller.bitcoinKey -> sellerFundsAfterCurrentStep._1))

    def validateCurrentTransactionSignatures(
        processor: TransactionProcessor,
        signatures: (TransactionSignature, TransactionSignature)): Boolean =
      validateTransactionSignatures(processor, getCurrentTransaction(processor), signatures)

    def nextStep: MicroPaymentChannel[C] = copy(currentStep = currentStep.next)

    def close(): Closing[C] = Closing(exchange, deposits)
  }

  case class Closing[C <: FiatCurrency](exchange: Exchange[C], deposits: Deposits) {

    val buyerFundsAfter = {
      val lastStepFunds = exchange.amounts.buyerFundsAfter(exchange.amounts.totalSteps.lastStep)
      (lastStepFunds._1 + exchange.amounts.buyerDeposit, lastStepFunds._2)
    }
    val sellerFundsAfter = {
      val lastStepFunds = exchange.amounts.sellerFundsAfter(exchange.amounts.totalSteps.lastStep)
      (exchange.amounts.sellerDeposit, lastStepFunds._2)
    }

    def getClosingTransaction(processor: TransactionProcessor): Transaction =
      processor.createTransaction(
        inputs = deposits.toSeq,
        outputs = Seq(
          exchange.buyer.bitcoinKey -> buyerFundsAfter._1,
          exchange.seller.bitcoinKey -> sellerFundsAfter._1))

    def validateClosingTransactionSignatures(
        processor: TransactionProcessor,
        signatures: (TransactionSignature, TransactionSignature)): Boolean =
      validateTransactionSignatures(processor, getClosingTransaction(processor), signatures)
  }

  private def validateTransactionSignatures(
      processor: TransactionProcessor,
      transaction: Transaction,
      signatures: (TransactionSignature, TransactionSignature)): Boolean = {
    signatures.productIterator.toSeq.zipWithIndex.forall {
      case (sign: TransactionSignature, index) =>
        processor.isValidSignature(transaction, index, sign)
    }
  }
}

