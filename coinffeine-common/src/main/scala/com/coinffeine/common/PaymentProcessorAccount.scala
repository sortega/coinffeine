package com.coinffeine.common

trait PaymentProcessorAccount {

}

case class OKPayAccount(token: String) extends PaymentProcessorAccount
