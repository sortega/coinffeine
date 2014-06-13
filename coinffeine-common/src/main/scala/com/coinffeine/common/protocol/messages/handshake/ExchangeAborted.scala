package com.coinffeine.common.protocol.messages.handshake

import com.coinffeine.common.Exchange
import com.coinffeine.common.protocol.messages.PublicMessage

case class ExchangeAborted (
  exchangeId: Exchange.Id,
  reason: String
) extends PublicMessage
