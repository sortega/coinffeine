package com.coinffeine.common.protocol.messages.exchange

import com.coinffeine.common.Exchange
import com.coinffeine.common.paymentprocessor.PaymentProcessor
import com.coinffeine.common.protocol.messages.PublicMessage

case class PaymentProof(exchangeId: Exchange.Id,
                        paymentId: PaymentProcessor#PaymentId) extends PublicMessage
