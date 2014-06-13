package com.coinffeine.common.blockchain

import com.google.bitcoin.core.{ECKey, Transaction, TransactionOutput}
import com.google.bitcoin.crypto.TransactionSignature

import com.coinffeine.common.BitcoinAmount

class DefaultTransactionProcessor extends TransactionProcessor {
  override def createTransaction(inputs: Seq[TransactionOutput], outputs: Seq[(ECKey, BitcoinAmount)], lockTime: Option[Long]): Transaction = ???

  override def multisign(tx: Transaction, inputIndex: Int, keys: ECKey*): TransactionSignature = ???

  override def sign(tx: Transaction, inputIndex: Int, key: ECKey): TransactionSignature = ???

  override def isValidSignature(transaction: Transaction, outputIndex: Int, signature: TransactionSignature): Boolean = ???
}
