package com.coinffeine.common.protocol.serialization

import java.math.BigDecimal
import java.util.Currency
import scala.collection.JavaConverters._

import com.google.protobuf.ByteString

import com.coinffeine.common.{BitcoinAmount, CurrencyAmount, FiatCurrency, PeerConnection}
import com.coinffeine.common.Currency.Bitcoin
import com.coinffeine.common.bitcoin.Hash
import com.coinffeine.common.exchange.{Both, Exchange}
import com.coinffeine.common.exchange.MicroPaymentChannel.Signatures
import com.coinffeine.common.protocol.messages.arbitration.CommitmentNotification
import com.coinffeine.common.protocol.messages.brokerage._
import com.coinffeine.common.protocol.messages.exchange._
import com.coinffeine.common.protocol.messages.handshake._
import com.coinffeine.common.protocol.protobuf.{CoinffeineProtobuf => msg}

/** Implicit conversion mappings for the protocol messages */
private[serialization] class DefaultProtoMappings(txSerialization: TransactionSerialization) {

  implicit val commitmentNotificationMapping =
    new ProtoMapping[CommitmentNotification, msg.CommitmentNotification] {

      override def fromProtobuf(commitment: msg.CommitmentNotification) = CommitmentNotification(
        exchangeId = Exchange.Id(commitment.getExchangeId),
        bothCommitments = Both(
          buyer = new Hash(commitment.getBuyerTxId.toByteArray),
          seller = new Hash(commitment.getSellerTxId.toByteArray)
        )
      )

      override def toProtobuf(commitment: CommitmentNotification) =
        msg.CommitmentNotification.newBuilder
          .setExchangeId(commitment.exchangeId.value)
          .setBuyerTxId(ByteString.copyFrom(commitment.bothCommitments.buyer.getBytes))
          .setSellerTxId(ByteString.copyFrom(commitment.bothCommitments.seller.getBytes))
          .build
    }

  implicit val enterExchangeMapping = new ProtoMapping[ExchangeCommitment, msg.ExchangeCommitment] {

      override def fromProtobuf(enter: msg.ExchangeCommitment) = ExchangeCommitment(
        commitmentTransaction = txSerialization.deserializeTransaction(
          enter.getCommitmentTransaction),
        exchangeId = Exchange.Id(enter.getExchangeId)
      )

      override def toProtobuf(enter: ExchangeCommitment) = msg.ExchangeCommitment.newBuilder
        .setExchangeId(enter.exchangeId.value)
        .setCommitmentTransaction(txSerialization.serialize(enter.commitmentTransaction)).build
    }

  implicit val exchangeAbortedMapping = new ProtoMapping[ExchangeAborted, msg.ExchangeAborted] {

    override def fromProtobuf(exchangeAborted: msg.ExchangeAborted) = ExchangeAborted(
      exchangeId = Exchange.Id(exchangeAborted.getExchangeId),
      reason = exchangeAborted.getReason
    )

    override def toProtobuf(exchangeAborted: ExchangeAborted) = msg.ExchangeAborted.newBuilder
      .setExchangeId(exchangeAborted.exchangeId.value)
      .setReason(exchangeAborted.reason)
      .build
  }

  implicit val exchangeRejectionMapping = new ProtoMapping[ExchangeRejection, msg.ExchangeRejection] {

    override def fromProtobuf(rejection: msg.ExchangeRejection) = ExchangeRejection(
      exchangeId = Exchange.Id(rejection.getExchangeId),
      reason = rejection.getReason
    )

    override def toProtobuf(rejection: ExchangeRejection) = msg.ExchangeRejection.newBuilder
      .setExchangeId(rejection.exchangeId.value)
      .setReason(rejection.reason)
      .build
  }

  implicit val fiatAmountMapping = new ProtoMapping[CurrencyAmount[FiatCurrency], msg.FiatAmount] {

    override def fromProtobuf(amount: msg.FiatAmount): CurrencyAmount[FiatCurrency] =
      FiatCurrency(Currency.getInstance(amount.getCurrency)).amount(
        BigDecimal.valueOf(amount.getValue, amount.getScale))

    override def toProtobuf(amount: CurrencyAmount[FiatCurrency]): msg.FiatAmount = msg.FiatAmount.newBuilder
      .setValue(amount.value.underlying().unscaledValue.longValue)
      .setScale(amount.value.scale)
      .setCurrency(amount.currency.javaCurrency.getCurrencyCode)
      .build
  }

  implicit val btcAmountMapping = new ProtoMapping[BitcoinAmount, msg.BtcAmount] {

    override def fromProtobuf(amount: msg.BtcAmount): BitcoinAmount =
      Bitcoin.amount(BigDecimal.valueOf(amount.getValue, amount.getScale))

    override def toProtobuf(amount: BitcoinAmount): msg.BtcAmount = msg.BtcAmount.newBuilder
      .setValue(amount.value.underlying().unscaledValue.longValue)
      .setScale(amount.value.scale)
      .build
  }

  implicit val marketMapping = new ProtoMapping[Market[FiatCurrency], msg.Market] {

    override def fromProtobuf(market: msg.Market): Market[FiatCurrency] =
      Market(FiatCurrency(Currency.getInstance(market.getCurrency)))

    override def toProtobuf(market: Market[FiatCurrency]): msg.Market = msg.Market.newBuilder
      .setCurrency(market.currency.javaCurrency.getCurrencyCode)
      .build
  }

  implicit val orderSetMapping = new ProtoMapping[OrderSet[FiatCurrency], msg.OrderSet] {

    override def fromProtobuf(orderSet: msg.OrderSet): OrderSet[FiatCurrency] = {
      val market = ProtoMapping.fromProtobuf(orderSet.getMarket)

      def volumeFromProtobuf(entries: Seq[msg.Order]): VolumeByPrice[FiatCurrency] = {
        val accum = VolumeByPrice.empty[FiatCurrency]
        entries.foldLeft(accum) { (volume, entry) => volume.increase(
            ProtoMapping.fromProtobuf(entry.getPrice),
            ProtoMapping.fromProtobuf(entry.getAmount)
        )}
      }

      OrderSet(
        market,
        bids = volumeFromProtobuf(orderSet.getBidsList.asScala),
        asks = volumeFromProtobuf(orderSet.getAsksList.asScala)
      )
    }

    override def toProtobuf(orderSet: OrderSet[FiatCurrency]): msg.OrderSet = {
      msg.OrderSet.newBuilder
        .setMarket(ProtoMapping.toProtobuf(orderSet.market))
        .addAllBids(volumeToProtobuf(orderSet.bids).asJava)
        .addAllAsks(volumeToProtobuf(orderSet.asks).asJava)
        .build
    }

    private def volumeToProtobuf(volume: VolumeByPrice[FiatCurrency]) = for {
      (price, amount) <- volume.entries
    } yield msg.Order.newBuilder
        .setPrice(ProtoMapping.toProtobuf(price))
        .setAmount(ProtoMapping.toProtobuf(amount))
        .build
  }

  implicit val orderMatchMapping = new ProtoMapping[OrderMatch, msg.OrderMatch] {

    override def fromProtobuf(orderMatch: msg.OrderMatch): OrderMatch = OrderMatch(
      exchangeId = Exchange.Id(orderMatch.getExchangeId),
      amount = ProtoMapping.fromProtobuf(orderMatch.getAmount),
      price = ProtoMapping.fromProtobuf(orderMatch.getPrice),
      participants = Both(
        buyer = PeerConnection.parse(orderMatch.getBuyer),
        seller = PeerConnection.parse(orderMatch.getSeller)
      )
    )

    override def toProtobuf(orderMatch: OrderMatch): msg.OrderMatch = msg.OrderMatch.newBuilder
      .setExchangeId(orderMatch.exchangeId.value)
      .setAmount(ProtoMapping.toProtobuf(orderMatch.amount))
      .setPrice(ProtoMapping.toProtobuf(orderMatch.price))
      .setBuyer(orderMatch.participants.buyer.toString)
      .setSeller(orderMatch.participants.seller.toString)
      .build
  }

  implicit val quoteMapping = new ProtoMapping[Quote[FiatCurrency], msg.Quote] {

    override def fromProtobuf(quote: msg.Quote): Quote[FiatCurrency] = {
      val bidOption =
        if (quote.hasHighestBid) Some(ProtoMapping.fromProtobuf(quote.getHighestBid)) else None
      val askOption =
        if (quote.hasLowestAsk) Some(ProtoMapping.fromProtobuf(quote.getLowestAsk)) else None
      val lastPriceOption =
        if (quote.hasLastPrice) Some(ProtoMapping.fromProtobuf(quote.getLastPrice)) else None
      val currency = FiatCurrency(Currency.getInstance(quote.getCurrency))
      def requireCorrectCurrency(amount: Option[CurrencyAmount[FiatCurrency]]): Unit = {
        require(amount.forall(_.currency == currency),
          s"Incorrect currency. Expected $currency, received ${amount.get.currency}")
      }
      requireCorrectCurrency(bidOption)
      requireCorrectCurrency(askOption)
      requireCorrectCurrency(lastPriceOption)
      Quote(currency, bidOption -> askOption, lastPriceOption)
    }

    override def toProtobuf(quote: Quote[FiatCurrency]): msg.Quote = {
      val Quote(currency, (bidOption, askOption), lastPriceOption) = quote
      val builder = msg.Quote.newBuilder
        .setCurrency(currency.javaCurrency.getCurrencyCode)
      bidOption.foreach(bid => builder.setHighestBid(ProtoMapping.toProtobuf(bid)))
      askOption.foreach(ask => builder.setLowestAsk(ProtoMapping.toProtobuf(ask)))
      lastPriceOption.foreach(lastPrice => builder.setLastPrice(ProtoMapping.toProtobuf(lastPrice)))
      builder.build
    }
  }

  implicit val openOrdersRequestMapping = new ProtoMapping[OpenOrdersRequest, msg.OpenOrdersRequest] {

    override def fromProtobuf(openOrdersRequest: msg.OpenOrdersRequest): OpenOrdersRequest = {
      val currency = FiatCurrency(Currency.getInstance(openOrdersRequest.getCurrency))
      OpenOrdersRequest(currency)
    }

    override def toProtobuf(openOrdersRequest: OpenOrdersRequest): msg.OpenOrdersRequest = {
      msg.OpenOrdersRequest.newBuilder.setCurrency(
        openOrdersRequest.currency.javaCurrency.getCurrencyCode).build
    }
  }

  implicit val openOrdersMapping = new ProtoMapping[OpenOrders[FiatCurrency], msg.OpenOrders] {

    override def fromProtobuf(openOrders: msg.OpenOrders): OpenOrders[FiatCurrency] =
      OpenOrders(ProtoMapping.fromProtobuf(openOrders.getOrderSet))

    override def toProtobuf(openOrders: OpenOrders[FiatCurrency]): msg.OpenOrders = {
      msg.OpenOrders.newBuilder
        .setOrderSet(ProtoMapping.toProtobuf[OrderSet[FiatCurrency], msg.OrderSet](openOrders.orders))
        .build
    }
  }

  implicit val quoteRequestMapping = new ProtoMapping[QuoteRequest, msg.QuoteRequest] {

    override def fromProtobuf(request: msg.QuoteRequest): QuoteRequest =
      QuoteRequest(FiatCurrency(Currency.getInstance(request.getCurrency)))

    override def toProtobuf(request: QuoteRequest): msg.QuoteRequest = msg.QuoteRequest.newBuilder
      .setCurrency(request.currency.javaCurrency.getCurrencyCode)
      .build
  }

  implicit val peerHandshakeMapping =
    new ProtoMapping[PeerHandshake, msg.PeerHandshake] {

      override def fromProtobuf(message: msg.PeerHandshake) = PeerHandshake(
        refundTx = txSerialization.deserializeTransaction(message.getRefundTx),
        exchangeId = Exchange.Id(message.getExchangeId),
        paymentProcessorAccount = message.getPaymentProcessorAccount
      )

      override def toProtobuf(message: PeerHandshake) =
        msg.PeerHandshake.newBuilder
          .setExchangeId(message.exchangeId.value)
          .setRefundTx(txSerialization.serialize(message.refundTx))
          .setPaymentProcessorAccount(message.paymentProcessorAccount)
          .build
    }

  implicit val peerHandshakeResponseMapping =
    new ProtoMapping[PeerHandshakeAccepted, msg.PeerHandshakeAccepted] {

      override def fromProtobuf(response: msg.PeerHandshakeAccepted) = PeerHandshakeAccepted(
        exchangeId = Exchange.Id(response.getExchangeId),
        refundSignature = txSerialization.deserializeSignature(response.getTransactionSignature)
      )

      override def toProtobuf(response: PeerHandshakeAccepted) =
        msg.PeerHandshakeAccepted.newBuilder
          .setExchangeId(response.exchangeId.value)
          .setTransactionSignature(txSerialization.serialize(response.refundSignature))
          .build()
    }

  implicit val offerSignatureMapping = new ProtoMapping[StepSignatures, msg.StepSignature] {

    override def fromProtobuf(message: msg.StepSignature) = StepSignatures(
      exchangeId = Exchange.Id(message.getExchangeId),
      step = message.getStep,
      signatures = Signatures(
        buyer =
          txSerialization.deserializeSignature(message.getBuyerDepositSignature),
        seller =
          txSerialization.deserializeSignature(message.getSellerDepositSignature)
      )
    )

    override def toProtobuf(obj: StepSignatures) = msg.StepSignature.newBuilder
      .setExchangeId(obj.exchangeId.value)
      .setStep(obj.step)
      .setBuyerDepositSignature(txSerialization.serialize(obj.signatures.buyer))
      .setSellerDepositSignature(txSerialization.serialize(obj.signatures.seller))
      .build()
  }

  implicit val paymentProofMapping = new ProtoMapping[PaymentProof, msg.PaymentProof] {

    override def fromProtobuf(message: msg.PaymentProof) = PaymentProof(
      exchangeId = Exchange.Id(message.getExchangeId),
      paymentId = message.getPaymentId
    )

    override def toProtobuf(obj: PaymentProof): msg.PaymentProof = msg.PaymentProof.newBuilder
      .setExchangeId(obj.exchangeId.value)
      .setPaymentId(obj.paymentId)
      .build()
  }
}
