package com.coinffeine.common.protocol.messages.handshake

import com.google.bitcoin.core.Transaction

import com.coinffeine.common.Exchange
import com.coinffeine.common.protocol.messages.PublicMessage

case class ExchangeCommitment(
  exchangeId: Exchange.Id,
  commitmentTransaction: Transaction
) extends PublicMessage
