package co.ledger.wallet.core.wallet.ethereum.api

import co.ledger.wallet.core.concurrent.AsyncCursor
import co.ledger.wallet.core.device.utils.EventEmitter
import co.ledger.wallet.core.net.HttpClient
import co.ledger.wallet.core.utils.DerivationPath
import co.ledger.wallet.core.wallet.ethereum.Wallet.WalletNotSetupException
import co.ledger.wallet.core.wallet.ethereum.{Transaction, _}
import co.ledger.wallet.core.wallet.ethereum.database.{AccountRow, DatabaseBackedWalletClient}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

/**
  *
  * AbstractApiWalletClient
  * ledger-wallet-ethereum-chrome
  *
  * Created by Pierre Pollastri on 14/06/2016.
  *
  * The MIT License (MIT)
  *
  * Copyright (c) 2016 Ledger
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  *
  */
abstract class AbstractApiWalletClient(override val name: String) extends Wallet with DatabaseBackedWalletClient {

  protected def transactionRestClient: AbstractTransactionRestClient
  protected def blockRestClient: AbstractBlockRestClient

  override def account(index: Int): Future[Account] = ???

  override def mostRecentBlock(): Future[Block] = init() flatMap {(_) =>
    queryLastBlock() map {(block) =>
      if (block == null)
        throw WalletNotSetupException()
      else
        block
    }
  }

  override def stop(): Unit = {

  }

  override def operations(from: Int, batchSize: Int): Future[AsyncCursor[Operation]] = ???

  override def synchronize(): Future[Unit] = {

    def synchronizeUntilEmptyAccount(syncToken: String, from: Int): Future[Unit] = {
      init().flatMap {(_) =>
        val accounts = _accounts.slice(from, _accounts.length)
        Future.sequence(accounts.map(_.synchronize(syncToken)))
      } flatMap {(_) =>
        if (_accounts.last.keyChain.issuedKeys != 0) {
          // Create an new account
          val newAccountIndex = _accounts.length
          createAccount(newAccountIndex) flatMap {(_) =>
            synchronizeUntilEmptyAccount(syncToken, newAccountIndex)
          }
        }
        else {
          Future.successful()
        }
      }

      Promise[Unit].future
    }

    transactionRestClient.obtainSyncToken() flatMap {(token) =>
      synchronizeUntilEmptyAccount(token, 0)
    }
    /*
    def synchronizeUntilEmptyAccount(syncToken: String, from: Int, block: ApiObjects.Block): Future[Unit]
    = {
      init().flatMap { (_) =>
        val accounts = _accounts.slice(from, _accounts.length)
        Future.sequence(accounts.map(_.synchronize(syncToken, block)).toList)
      } flatMap { (_) =>
        if (_accounts.last.keyChain.getIssuedExternalKeys != 0 ||
          _accounts.last.keyChain.getIssuedInternalKeys != 0) {
          // Create a new account
          val newAccountIndex = _accounts.length
          createAccount(_accounts.length, xpubProvider).flatMap { (_) =>
            _accounts = Array[ApiAccountClient]()
            synchronizeUntilEmptyAccount(syncToken, newAccountIndex, block)
          }
        } else {
          Future.successful()
        }
      }
    }
    transactionRestClient.requestSyncToken().flatMap {(token) =>
      blockRestClient.mostRecentBlock().flatMap {(block) =>
        synchronizeUntilEmptyAccount(token, 0, block).flatMap { unit =>
          transactionRestClient.deleteSyncToken(token)
        }
      }
    }
    */
  }

  def ethereumAccountProvider: EthereumAccountProvider

  override def accounts(): Future[Array[Account]] = ???

  override def isSynchronizing(): Future[Boolean] = ???

  override def balance(): Future[Ether] = ???

  override def eventEmitter: EventEmitter = ???

  override def pushTransaction(transaction: Transaction): Future[Unit] = ???

  private def createAccount(index: Int): Future[Account] = {
    ethereumAccountProvider.getEthereumAccount(DerivationPath(s"44'/60'/$index'/0/0")).map {(ethereumAccount) =>
      val account = new AccountRow(index, ethereumAccount.toString)
      putAccount(account)
      _accounts = Array(newAccountClient(account))
      _accounts.last
    }
  }

  private def init(): Future[Unit] = {
    _initPromise.getOrElse({
      _initPromise = Some(Promise[Unit]())
      _initPromise.get.completeWith(queryAccounts(0, Int.MaxValue) flatMap {(accounts) =>
        println("Got accounts " + accounts.length)
        _accounts = accounts.map(newAccountClient(_))
        if (_accounts.length == 0) {
          createAccount(0).map((_) => ())
        } else {
          Future.successful()
        }
      })
      _initPromise.get
    }).future
  }

  private var _accounts: Array[AbstractApiAccountClient] = null
  private var _initPromise: Option[Promise[Unit]] = None

  protected def newAccountClient(accountRow: AccountRow): AbstractApiAccountClient

}
