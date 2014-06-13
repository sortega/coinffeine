package com.coinffeine.common.protocol.messages.handshake

import com.google.bitcoin.core.Transaction

import com.coinffeine.common.Exchange
import com.coinffeine.common.protocol.messages.PublicMessage

case class RefundTxSignatureRequest(
  exchangeId : Exchange.Id,
  refundTx: Transaction
) extends PublicMessage
