package com.coinffeine.common.protocol.messages.exchange

import com.google.bitcoin.crypto.TransactionSignature

import com.coinffeine.common.protocol.messages.PublicMessage
import com.coinffeine.common.protocol.TransactionSignatureUtils
import com.coinffeine.common.Exchange

case class StepSignatures(
    exchangeId: Exchange.Id, idx0Signature: TransactionSignature, idx1Signature: TransactionSignature)
  extends PublicMessage {

  override def equals(that: Any) = that match {
    case newStepStart: StepSignatures => (newStepStart.exchangeId == exchangeId) &&
      TransactionSignatureUtils.equals(newStepStart.idx0Signature, idx0Signature) &&
      TransactionSignatureUtils.equals(newStepStart.idx1Signature, idx1Signature)
    case _ => false
  }
}

object StepSignatures {
  def apply(
      exchangeId: Exchange.Id,
      signatures: (TransactionSignature, TransactionSignature)): StepSignatures =
    StepSignatures(exchangeId, signatures._1, signatures._2)
}
