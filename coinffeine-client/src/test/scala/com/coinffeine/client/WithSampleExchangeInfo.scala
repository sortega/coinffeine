package com.coinffeine.client

import scala.concurrent.duration._

import com.google.bitcoin.core.ECKey
import com.google.bitcoin.params.TestNet3Params

import com.coinffeine.common.{Currency, Exchange, PeerConnection}
import com.coinffeine.common.Currency.Implicits._
import com.coinffeine.common.network.UnitTestNetworkComponent

@deprecated
trait WithSampleExchangeInfo extends UnitTestNetworkComponent {

  @deprecated
  val sampleExchangeInfo = ExchangeInfo(
    "id",
    PeerConnection("counterpart"),
    PeerConnection("broker"),
    network,
    userKey = new ECKey(),
    userFiatAddress = "",
    counterpartKey = new ECKey(),
    counterpartFiatAddress = "",
    btcExchangeAmount = 10 BTC,
    fiatExchangeAmount = 10 EUR,
    steps = 10,
    lockTime = 25)

  val sampleExchange: Exchange[Currency.Euro.type] = Exchange[Currency.Euro.type](
    id = Exchange.Id.random(),
    parameters = Exchange.Parameters(
      lockTime = 25,
      commitmentConfirmations = 1,
      resubmitRefundSignatureTimeout = 3.seconds,
      refundSignatureAbortTimeout = 10.seconds
    ),
    buyer = Exchange.PeerInfo(PeerConnection("buyer"), "id001", new ECKey()),
    seller = Exchange.PeerInfo(PeerConnection("seller"), "id001", new ECKey()),
    broker = Exchange.BrokerInfo(PeerConnection("broker")),
    amounts = Exchange.Amounts(
      bitcoinNetwork = TestNet3Params.get(),
      bitcoinAmount = 10.BTC,
      fiatAmount = 10.EUR,
      totalSteps = Exchange.TotalSteps(10)
    )
  )
}
