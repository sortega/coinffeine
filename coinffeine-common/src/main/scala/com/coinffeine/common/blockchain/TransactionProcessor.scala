package com.coinffeine.common.blockchain

import com.google.bitcoin.core.{ECKey, Transaction, TransactionOutput}
import com.google.bitcoin.crypto.TransactionSignature

import com.coinffeine.common.BitcoinAmount

/** This trait encapsulates the transaction processing actions. */
trait TransactionProcessor {

  def createTransaction(inputs: Seq[TransactionOutput],
                        outputs: Seq[(ECKey, BitcoinAmount)],
                        lockTime: Option[Long] = None): Transaction

  def multisign(tx: Transaction, inputIndex: Int, keys: ECKey*): TransactionSignature

  def sign(tx: Transaction, inputIndex: Int, key: ECKey): TransactionSignature

  def isValidSignature(transaction: Transaction,
                       outputIndex: Int,
                       signature: TransactionSignature): Boolean

  def areValidSignatures(transaction: Transaction,
                         signatures: (TransactionSignature, TransactionSignature)): Boolean = {
    signatures.productIterator.toSeq.zipWithIndex.forall {
      case (sign: TransactionSignature, index) => isValidSignature(transaction, index, sign)
    }
  }
}

object TransactionProcessor {
  trait Component {
    def transactionProcessor: TransactionProcessor
  }
}
