package com.coinffeine.common.protocol.messages.arbitration

import com.google.bitcoin.core.Sha256Hash

import com.coinffeine.common.Exchange
import com.coinffeine.common.protocol.messages.PublicMessage

case class CommitmentNotification(
  exchangeId: Exchange.Id,
  buyerTxId: Sha256Hash,
  sellerTxId: Sha256Hash
) extends PublicMessage
