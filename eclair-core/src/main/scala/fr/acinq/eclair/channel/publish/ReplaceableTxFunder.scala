/*
 * Copyright 2021 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.publish

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoinscala.{ByteVector32, KotlinUtils, OutPoint, Satoshi, Script, ScriptWitness, Transaction, TxIn, TxOut, computeScriptAddress}
import fr.acinq.eclair.NotificationsLogger.{NotifyNodeOperator, logFatalError}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{FundPsbtOptions, FundPsbtResponse, FundTransactionOptions}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{NodeParams, NotificationsLogger}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 20/12/2021.
 */

/**
 * This actor funds a replaceable transaction to reach the requested feerate, signs it, and returns the resulting
 * transaction to the caller. Whenever possible, we avoid adding new inputs.
 * This actor does not publish the resulting transaction.
 */
object ReplaceableTxFunder {

  // @formatter:off
  sealed trait Command
  case class FundTransaction(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, tx: Either[FundedTx, ReplaceableTxWithWitnessData], targetFeerate: FeeratePerKw) extends Command

  private case class AddInputsOk(tx: ReplaceableTxWithWitnessData, totalAmountIn: Satoshi) extends Command
  private case class AddInputsFailed(reason: Throwable) extends Command
  private case class SignWalletInputsOk(signedTx: Transaction) extends Command
  private case class SignWalletInputsFailed(reason: Throwable) extends Command
  private case object UtxosUnlocked extends Command
  // @formatter:on

  case class FundedTx(signedTxWithWitnessData: ReplaceableTxWithWitnessData, totalAmountIn: Satoshi, feerate: FeeratePerKw) {
    require(signedTxWithWitnessData.txInfo.tx.txIn.nonEmpty, "funded transaction must have inputs")
    require(signedTxWithWitnessData.txInfo.tx.txOut.nonEmpty, "funded transaction must have outputs")
    val signedTx: Transaction = signedTxWithWitnessData.txInfo.tx
    val fee: Satoshi = totalAmountIn - signedTx.txOut.map(_.amount).sum
  }

  // @formatter:off
  sealed trait FundingResult
  case class TransactionReady(fundedTx: FundedTx) extends FundingResult
  case class FundingFailed(reason: TxPublisher.TxRejectedReason) extends FundingResult
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, txPublishContext: TxPublishContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(txPublishContext.mdc()) {
        Behaviors.receiveMessagePartial {
          case FundTransaction(replyTo, cmd, tx, targetFeerate) =>
            val txFunder = new ReplaceableTxFunder(nodeParams, replyTo, cmd, bitcoinClient, context)
            tx match {
              case Right(txWithWitnessData) => txFunder.fund(txWithWitnessData, targetFeerate)
              case Left(previousTx) => txFunder.bump(previousTx, targetFeerate)
            }
        }
      }
    }
  }

  /**
   * Adjust the amount of the change output of an anchor tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustAnchorOutputChange(unsignedTx: ClaimLocalAnchorWithWitnessData, commitTx: Transaction, amountIn: Satoshi, commitFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi): ClaimLocalAnchorWithWitnessData = {
    require(unsignedTx.txInfo.tx.txOut.size == 1, "funded transaction should have a single change output")
    // We take into account witness weight and adjust the fee to match our desired feerate.
    val dummySignedClaimAnchorTx = addSigs(unsignedTx.txInfo, PlaceHolderSig)
    // NB: we assume that our bitcoind wallet uses only P2WPKH inputs when funding txs.
    val estimatedWeight = commitTx.weight() + dummySignedClaimAnchorTx.tx.weight() + claimP2WPKHOutputWitnessWeight * (dummySignedClaimAnchorTx.tx.txIn.size - 1)
    val targetFee = weight2fee(targetFeerate, estimatedWeight) - weight2fee(commitFeerate, commitTx.weight())
    val amountOut = dustLimit.max(amountIn - targetFee)
    val updatedAnchorTx = unsignedTx.updateTx(unsignedTx.txInfo.tx.copy(txOut = Seq(unsignedTx.txInfo.tx.txOut.head.copy(amount = amountOut))))
    updatedAnchorTx
  }

  private def dummySignedCommitTx(commitments: Commitments): CommitTx = {
    val unsignedCommitTx = commitments.localCommit.commitTxAndRemoteSig.commitTx
    addSigs(unsignedCommitTx, PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderSig, PlaceHolderSig)
  }

  /**
   * Adjust the change output of an htlc tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustHtlcTxChange(unsignedTx: HtlcWithWitnessData, amountIn: Satoshi, targetFeerate: FeeratePerKw, dustLimit: Satoshi, commitmentFormat: CommitmentFormat): HtlcWithWitnessData = {
    require(unsignedTx.txInfo.tx.txOut.size <= 2, "funded transaction should have at most one change output")
    val dummySignedTx = unsignedTx.txInfo match {
      case tx: HtlcSuccessTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, ByteVector32.Zeroes, commitmentFormat)
      case tx: HtlcTimeoutTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, commitmentFormat)
    }
    // We adjust the change output to obtain the targeted feerate.
    val estimatedWeight = dummySignedTx.tx.weight() + claimP2WPKHOutputWitnessWeight * (dummySignedTx.tx.txIn.size - 1)
    val targetFee = weight2fee(targetFeerate, estimatedWeight)
    val changeAmount = amountIn - dummySignedTx.tx.txOut.head.amount - targetFee
    val updatedHtlcTx = if (dummySignedTx.tx.txOut.length == 2 && changeAmount >= dustLimit) {
      unsignedTx.updateTx(unsignedTx.txInfo.tx.copy(txOut = Seq(unsignedTx.txInfo.tx.txOut.head, unsignedTx.txInfo.tx.txOut.last.copy(amount = changeAmount))))
    } else {
      unsignedTx.updateTx(unsignedTx.txInfo.tx.copy(txOut = Seq(unsignedTx.txInfo.tx.txOut.head)))
    }
    updatedHtlcTx
  }

  /**
   * Adjust the main output of a claim-htlc tx to match our target feerate.
   * If the resulting output is too small, we skip the transaction.
   */
  def adjustClaimHtlcTxOutput(claimHtlcTx: ClaimHtlcWithWitnessData, targetFeerate: FeeratePerKw, dustLimit: Satoshi): Either[TxGenerationSkipped, ClaimHtlcWithWitnessData] = {
    require(claimHtlcTx.txInfo.tx.txIn.size == 1, "claim-htlc transaction should have a single input")
    require(claimHtlcTx.txInfo.tx.txOut.size == 1, "claim-htlc transaction should have a single output")
    val dummySignedTx = claimHtlcTx.txInfo match {
      case tx: ClaimHtlcSuccessTx => addSigs(tx, PlaceHolderSig, ByteVector32.Zeroes)
      case tx: ClaimHtlcTimeoutTx => addSigs(tx, PlaceHolderSig)
      case tx: LegacyClaimHtlcSuccessTx => tx
    }
    val targetFee = weight2fee(targetFeerate, dummySignedTx.tx.weight())
    val outputAmount = claimHtlcTx.txInfo.amountIn - targetFee
    if (outputAmount < dustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val updatedClaimHtlcTx = claimHtlcTx match {
        // NB: we don't modify legacy claim-htlc-success, it's already signed.
        case legacyClaimHtlcSuccess: LegacyClaimHtlcSuccessWithWitnessData => legacyClaimHtlcSuccess
        case _ => claimHtlcTx.updateTx(claimHtlcTx.txInfo.tx.copy(txOut = Seq(claimHtlcTx.txInfo.tx.txOut.head.copy(amount = outputAmount))))
      }
      Right(updatedClaimHtlcTx)
    }
  }

  // @formatter:off
  sealed trait AdjustPreviousTxOutputResult
  object AdjustPreviousTxOutputResult {
    case class Skip(reason: String) extends AdjustPreviousTxOutputResult
    case class AddWalletInputs(previousTx: ReplaceableTxWithWalletInputs) extends AdjustPreviousTxOutputResult
    case class TxOutputAdjusted(updatedTx: ReplaceableTxWithWitnessData) extends AdjustPreviousTxOutputResult
  }
  // @formatter:on

  /**
   * Adjust the outputs of a transaction that was previously published at a lower feerate.
   * If the current set of inputs doesn't let us to reach the target feerate, we should request new wallet inputs from bitcoind.
   */
  def adjustPreviousTxOutput(previousTx: FundedTx, targetFeerate: FeeratePerKw, commitments: Commitments): AdjustPreviousTxOutputResult = {
    val dustLimit = commitments.localParams.dustLimit
    val targetFee = previousTx.signedTxWithWitnessData match {
      case _: ClaimLocalAnchorWithWitnessData =>
        val commitTx = dummySignedCommitTx(commitments)
        val totalWeight = previousTx.signedTx.weight() + commitTx.tx.weight()
        weight2fee(targetFeerate, totalWeight) - commitTx.fee
      case _ => weight2fee(targetFeerate, previousTx.signedTx.weight())
    }
    previousTx.signedTxWithWitnessData match {
      case claimLocalAnchor: ClaimLocalAnchorWithWitnessData =>
        val changeAmount = previousTx.totalAmountIn - targetFee
        if (changeAmount < dustLimit) {
          AdjustPreviousTxOutputResult.AddWalletInputs(claimLocalAnchor)
        } else {
          val updatedTxOut = Seq(claimLocalAnchor.txInfo.tx.txOut.head.copy(amount = changeAmount))
          AdjustPreviousTxOutputResult.TxOutputAdjusted(claimLocalAnchor.updateTx(claimLocalAnchor.txInfo.tx.copy(txOut = updatedTxOut)))
        }
      case htlcTx: HtlcWithWitnessData =>
        if (htlcTx.txInfo.tx.txOut.length <= 1) {
          // There is no change output, so we can't increase the fees without adding new inputs.
          AdjustPreviousTxOutputResult.AddWalletInputs(htlcTx)
        } else {
          val htlcAmount = htlcTx.txInfo.tx.txOut.head.amount
          val changeAmount = previousTx.totalAmountIn - targetFee - htlcAmount
          if (dustLimit <= changeAmount) {
            val updatedTxOut = Seq(htlcTx.txInfo.tx.txOut.head, htlcTx.txInfo.tx.txOut.last.copy(amount = changeAmount))
            AdjustPreviousTxOutputResult.TxOutputAdjusted(htlcTx.updateTx(htlcTx.txInfo.tx.copy(txOut = updatedTxOut)))
          } else {
            // We try removing the change output to see if it provides a high enough feerate.
            val htlcTxNoChange = htlcTx.updateTx(htlcTx.txInfo.tx.copy(txOut = Seq(htlcTx.txInfo.tx.txOut.head)))
            val fee = previousTx.totalAmountIn - htlcAmount
            if (fee <= htlcAmount) {
              val feerate = fee2rate(fee, htlcTxNoChange.txInfo.tx.weight())
              if (targetFeerate <= feerate) {
                // Without the change output, we're able to reach our desired feerate.
                AdjustPreviousTxOutputResult.TxOutputAdjusted(htlcTxNoChange)
              } else {
                // Even without the change output, the feerate is too low: we must add new wallet inputs.
                AdjustPreviousTxOutputResult.AddWalletInputs(htlcTx)
              }
            } else {
              AdjustPreviousTxOutputResult.Skip("fee higher than htlc amount")
            }
          }
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        val updatedAmount = previousTx.totalAmountIn - targetFee
        if (updatedAmount < dustLimit) {
          AdjustPreviousTxOutputResult.Skip("fee higher than htlc amount")
        } else {
          val updatedTxOut = Seq(claimHtlcTx.txInfo.tx.txOut.head.copy(amount = updatedAmount))
          claimHtlcTx match {
            // NB: we don't modify legacy claim-htlc-success, it's already signed.
            case _: LegacyClaimHtlcSuccessWithWitnessData => AdjustPreviousTxOutputResult.Skip("legacy claim-htlc-success should not be updated")
            case _ => AdjustPreviousTxOutputResult.TxOutputAdjusted(claimHtlcTx.updateTx(claimHtlcTx.txInfo.tx.copy(txOut = updatedTxOut)))
          }
        }
    }
  }

}

private class ReplaceableTxFunder(nodeParams: NodeParams,
                                  replyTo: ActorRef[ReplaceableTxFunder.FundingResult],
                                  cmd: TxPublisher.PublishReplaceableTx,
                                  bitcoinClient: BitcoinCoreClient,
                                  context: ActorContext[ReplaceableTxFunder.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxFunder._
  import nodeParams.{channelKeyManager => keyManager}

  private val log = context.log

  def fund(txWithWitnessData: ReplaceableTxWithWitnessData, targetFeerate: FeeratePerKw): Behavior[Command] = {
    txWithWitnessData match {
      case claimLocalAnchor: ClaimLocalAnchorWithWitnessData =>
        val commitFeerate = cmd.commitments.localCommit.spec.commitTxFeerate
        if (targetFeerate <= commitFeerate) {
          log.info("skipping {}: commit feerate is high enough (feerate={})", cmd.desc, commitFeerate)
          // We set retry = true in case the on-chain feerate rises before the commit tx is confirmed: if that happens
          // we'll want to claim our anchor to raise the feerate of the commit tx and get it confirmed faster.
          replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
          Behaviors.stopped
        } else {
          addWalletInputs(claimLocalAnchor, targetFeerate)
        }
      case htlcTx: HtlcWithWitnessData =>
        val htlcFeerate = cmd.commitments.localCommit.spec.htlcTxFeerate(cmd.commitments.commitmentFormat)
        if (targetFeerate <= htlcFeerate) {
          log.info("publishing {} without adding inputs: txid={}", cmd.desc, htlcTx.txInfo.tx.txid)
          sign(txWithWitnessData, htlcFeerate, htlcTx.txInfo.amountIn)
        } else {
          addWalletInputs(htlcTx, targetFeerate)
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        adjustClaimHtlcTxOutput(claimHtlcTx, targetFeerate, cmd.commitments.localParams.dustLimit) match {
          case Left(reason) =>
            // The htlc isn't economical to claim at the current feerate, but if the feerate goes down, we may want to claim it later.
            log.warn("skipping {}: {} (feerate={})", cmd.desc, reason, targetFeerate)
            replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
            Behaviors.stopped
          case Right(updatedClaimHtlcTx) =>
            sign(updatedClaimHtlcTx, targetFeerate, updatedClaimHtlcTx.txInfo.amountIn)
        }
    }
  }

  def bump(previousTx: FundedTx, targetFeerate: FeeratePerKw): Behavior[Command] = {
    def removeWitness1(txIn: TxIn): TxIn = txIn.copy(witness = ScriptWitness.empty)

    def removeWitness2(inputs: Seq[TxIn]): Seq[TxIn] = inputs.map(removeWitness1)

    def removeWitness(tx: Transaction): Transaction = tx.copy(txIn = removeWitness2(tx.txIn))

    adjustPreviousTxOutput(previousTx, targetFeerate, cmd.commitments) match {
      case AdjustPreviousTxOutputResult.Skip(reason) =>
        log.warn("skipping {} fee bumping: {} (feerate={})", cmd.desc, reason, targetFeerate)
        replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case AdjustPreviousTxOutputResult.TxOutputAdjusted(updatedTx) =>
        log.debug("bumping {} fees without adding new inputs: txid={}", cmd.desc, updatedTx.txInfo.tx.txid)
        val updatedTx1 = updatedTx.updateTx(removeWitness(updatedTx.txInfo.tx))
        sign(updatedTx1, targetFeerate, previousTx.totalAmountIn)
      case AdjustPreviousTxOutputResult.AddWalletInputs(tx) =>
        log.debug("bumping {} fees requires adding new inputs (feerate={})", cmd.desc, targetFeerate)
        // We restore the original transaction (remove previous attempt's wallet inputs).
        val resetTx = tx.updateTx(cmd.txInfo.tx)
        addWalletInputs(resetTx, targetFeerate)
    }
  }

  def addWalletInputs(txWithWitnessData: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw): Behavior[Command] = {
    context.pipeToSelf(addInputs(txWithWitnessData, targetFeerate, cmd.commitments)) {
      case Success((fundedTx, totalAmountIn)) => AddInputsOk(fundedTx, totalAmountIn)
      case Failure(reason) => AddInputsFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case AddInputsOk(fundedTx, totalAmountIn) =>
        log.info("added {} wallet input(s) and {} wallet output(s) to {}", fundedTx.txInfo.tx.txIn.length - 1, fundedTx.txInfo.tx.txOut.length - 1, cmd.desc)
        sign(fundedTx, targetFeerate, totalAmountIn)
      case AddInputsFailed(reason) =>
        if (reason.getMessage.contains("Insufficient funds")) {
          val nodeOperatorMessage =
            s"""Insufficient funds in bitcoin wallet to set feerate=$targetFeerate for ${cmd.desc}.
               |You should add more utxos to your bitcoin wallet to guarantee funds safety.
               |Attempts will be made periodically to re-publish this transaction.
               |""".stripMargin
          context.system.eventStream ! EventStream.Publish(NotifyNodeOperator(NotificationsLogger.Warning, nodeOperatorMessage))
          log.warn("cannot add inputs to {}: {}", cmd.desc, reason.getMessage)
        } else {
          log.error(s"cannot add inputs to ${cmd.desc}: ", reason)
        }
        replyTo ! FundingFailed(TxPublisher.TxRejectedReason.CouldNotFund)
        Behaviors.stopped
    }
  }

  def sign(fundedTx: ReplaceableTxWithWitnessData, txFeerate: FeeratePerKw, amountIn: Satoshi): Behavior[Command] = {
    val channelKeyPath = keyManager.keyPath(cmd.commitments.localParams, cmd.commitments.channelConfig)
    fundedTx match {
      case ClaimLocalAnchorWithWitnessData(anchorTx) =>
        val localSig = keyManager.sign(anchorTx, keyManager.fundingPublicKey(cmd.commitments.localParams.fundingKeyPath), TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedTx = ClaimLocalAnchorWithWitnessData(addSigs(anchorTx, localSig))
        signWalletInputs(signedTx, txFeerate, amountIn)
      case htlcTx: HtlcWithWitnessData =>
        val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, cmd.commitments.localCommit.index)
        val localHtlcBasepoint = keyManager.htlcPoint(channelKeyPath)
        val localSig = keyManager.sign(htlcTx.txInfo, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedTx = htlcTx match {
          case htlcSuccess: HtlcSuccessWithWitnessData => htlcSuccess.copy(txInfo = addSigs(htlcSuccess.txInfo, localSig, htlcSuccess.remoteSig, htlcSuccess.preimage, cmd.commitments.commitmentFormat))
          case htlcTimeout: HtlcTimeoutWithWitnessData => htlcTimeout.copy(txInfo = addSigs(htlcTimeout.txInfo, localSig, htlcTimeout.remoteSig, cmd.commitments.commitmentFormat))
        }
        val hasWalletInputs = htlcTx.txInfo.tx.txIn.size > 1
        if (hasWalletInputs) {
          signWalletInputs(signedTx, txFeerate, amountIn)
        } else {
          replyTo ! TransactionReady(FundedTx(signedTx, amountIn, txFeerate))
          Behaviors.stopped
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        val sig = keyManager.sign(claimHtlcTx.txInfo, keyManager.htlcPoint(channelKeyPath), cmd.commitments.remoteCommit.remotePerCommitmentPoint, TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedTx = claimHtlcTx match {
          case ClaimHtlcSuccessWithWitnessData(txInfo, preimage) => ClaimHtlcSuccessWithWitnessData(addSigs(txInfo, sig, preimage), preimage)
          case legacyClaimHtlcSuccess: LegacyClaimHtlcSuccessWithWitnessData => legacyClaimHtlcSuccess
          case ClaimHtlcTimeoutWithWitnessData(txInfo) => ClaimHtlcTimeoutWithWitnessData(addSigs(txInfo, sig))
        }
        replyTo ! TransactionReady(FundedTx(signedTx, amountIn, txFeerate))
        Behaviors.stopped
    }
  }

  def signWalletInputs(locallySignedTx: ReplaceableTxWithWalletInputs, txFeerate: FeeratePerKw, amountIn: Satoshi): Behavior[Command] = {
    import KotlinUtils._

    locallySignedTx match {
      case ClaimLocalAnchorWithWitnessData(anchorTx) =>
        val psbt1 = new Psbt(anchorTx.tx)
        val psbt2 = psbt1.updateWitnessInput(anchorTx.input.outPoint, anchorTx.input.txOut, null, fr.acinq.bitcoin.Script.parse(anchorTx.input.redeemScript), null, java.util.Map.of()).getRight
        val psbt3 = psbt2.finalizeWitnessInput(0, anchorTx.tx.txIn.head.witness).getRight
        context.pipeToSelf(bitcoinClient.processPsbt(psbt3)) {
          case Success(processPsbtResponse) =>
            val extracted = processPsbtResponse.psbt.extract()
            if (extracted.isLeft) {
              SignWalletInputsFailed(new RuntimeException(extracted.getLeft.toString))
            } else {
              SignWalletInputsOk(extracted.getRight)
            }
          case Failure(reason) => SignWalletInputsFailed(reason)
        }
      case htlcTx: HtlcWithWitnessData =>
        val psbt1 = new Psbt(htlcTx.txInfo.tx)
        val psbt2 = psbt1.updateWitnessInput(htlcTx.txInfo.input.outPoint, htlcTx.txInfo.input.txOut, null, fr.acinq.bitcoin.Script.parse(htlcTx.txInfo.input.redeemScript), null, java.util.Map.of()).getRight
        val psbt3 = psbt2.finalizeWitnessInput(0, htlcTx.txInfo.tx.txIn.head.witness).getRight
        context.pipeToSelf(bitcoinClient.processPsbt(psbt3)) {
          case Success(processPsbtResponse) =>
            val extracted = processPsbtResponse.psbt.extract()
            if (extracted.isLeft) {
              SignWalletInputsFailed(new RuntimeException(extracted.getLeft.toString))
            } else {
              SignWalletInputsOk(extracted.getRight)
            }
          case Failure(reason) => SignWalletInputsFailed(reason)
        }
    }
    Behaviors.receiveMessagePartial {
      case SignWalletInputsOk(signedTx) =>
        val fullySignedTx = locallySignedTx.updateTx(signedTx)
        replyTo ! TransactionReady(FundedTx(fullySignedTx, amountIn, txFeerate))
        Behaviors.stopped
      case SignWalletInputsFailed(reason) =>
        log.error(s"cannot sign ${cmd.desc}: ", reason)
        // We reply with the failure only once the utxos are unlocked, otherwise there is a risk that our parent stops
        // itself, which will automatically stop us before we had a chance to unlock them.
        unlockAndStop(locallySignedTx.txInfo.input.outPoint, locallySignedTx.txInfo.tx, TxPublisher.TxRejectedReason.UnknownTxFailure)
    }
  }

  def unlockAndStop(input: OutPoint, tx: Transaction, failure: TxPublisher.TxRejectedReason): Behavior[Command] = {
    val toUnlock = tx.txIn.filterNot(_.outPoint == input).map(_.outPoint)
    log.debug("unlocking utxos={}", toUnlock.mkString(", "))
    context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock))(_ => UtxosUnlocked)
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked =>
        log.debug("utxos unlocked")
        replyTo ! FundingFailed(failure)
        Behaviors.stopped
    }
  }

  private def addInputs(tx: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(ReplaceableTxWithWalletInputs, Satoshi)] = {
    tx match {
      case anchorTx: ClaimLocalAnchorWithWitnessData => addInputs(anchorTx, targetFeerate, commitments)
      case htlcTx: HtlcWithWitnessData => addInputs(htlcTx, targetFeerate, commitments)
    }
  }

  private def addInputs(anchorTx: ClaimLocalAnchorWithWitnessData, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(ClaimLocalAnchorWithWitnessData, Satoshi)] = {
    import fr.acinq.bitcoinscala.KotlinUtils._

    val dustLimit = commitments.localParams.dustLimit
    val commitFeerate = commitments.localCommit.spec.commitTxFeerate
    val commitTx = dummySignedCommitTx(commitments).tx
    // We want the feerate of the package (commit tx + tx spending anchor) to equal targetFeerate.
    // Thus we have: anchorFeerate = targetFeerate + (weight-commit-tx / weight-anchor-tx) * (targetFeerate - commitTxFeerate)
    // If we use the smallest weight possible for the anchor tx, the feerate we use will thus be greater than what we want,
    // and we can adjust it afterwards by raising the change output amount.
    val anchorFeerate = targetFeerate + FeeratePerKw(targetFeerate.feerate - commitFeerate.feerate) * commitTx.weight() / claimAnchorOutputMinWeight
    // NB: fundrawtransaction requires at least one output, and may add at most one additional change output.
    // Since the purpose of this transaction is just to do a CPFP, the resulting tx should have a single change output
    // (note that bitcoind doesn't let us publish a transaction with no outputs).
    // To work around these limitations, we start with a dummy output and later merge that dummy output with the optional
    // change output added by bitcoind.
    // NB: fundrawtransaction doesn't support non-wallet inputs, so we have to remove our anchor input and re-add it later.
    // That means bitcoind will not take our anchor input's weight into account when adding inputs to set the fee.
    // That's ok, we can increase the fee later by decreasing the output amount. But we need to ensure we'll have enough
    // to cover the weight of our anchor input, which is why we set it to the following value.
    val dummyChangeAmount = weight2fee(anchorFeerate, claimAnchorOutputMinWeight) + dustLimit
    val Some(address) = computeScriptAddress(nodeParams.chainHash, Script.pay2wpkh(PlaceHolderPubKey))

    // merge outptuts if needed to get a PSBT with a single output
    def makeSingleOutput(fundPsbtResponse: FundPsbtResponse): Future[Psbt] = {
      fundPsbtResponse.changePosition match {
        case Some(changePos) =>
          // add our main output to the change output
          val changeOutput = fundPsbtResponse.psbt.getGlobal.getTx.txOut.get(changePos)
          val changeOutput1 = changeOutput.updateAmount(changeOutput.amount + dummyChangeAmount)
          val psbt = fundPsbtResponse.psbt
          val global = psbt.getGlobal
          val tx = global.getTx
          val tx1 = tx.updateOutputs(java.util.List.of(changeOutput1))
          val global1 = global.copy(global.getVersion, tx1, global.getExtendedPublicKeys, global.getUnknown)
          val psbt1 = psbt.copy(global1, psbt.getInputs, java.util.List.of(psbt.getOutputs.get(changePos)))
          Future.successful(psbt1)
        case None =>
          // replace our main output with a dummy change output
          bitcoinClient.getChangeAddress().map(pubkeyHash => {
            val changeOutput1 = TxOut(dummyChangeAmount, Script.pay2wpkh(pubkeyHash))
            val global = fundPsbtResponse.psbt.getGlobal
            val tx = global.getTx
            val tx1 = tx.updateOutputs(java.util.List.of(changeOutput1))
            val global1 = global.copy(global.getVersion, tx1, global.getExtendedPublicKeys, global.getUnknown)
            fundPsbtResponse.psbt.copy(global1, fundPsbtResponse.psbt.getInputs, fundPsbtResponse.psbt.getOutputs)
          })
      }
    }

    for {
      fundPsbtResponse <- bitcoinClient.fundPsbt(Seq(address -> dummyChangeAmount), 0, FundPsbtOptions(anchorFeerate, lockUtxos = true, changePosition = Some(1)))
      psbt <- makeSingleOutput(fundPsbtResponse)
      // NB: we insert the anchor input in the *first* position because our signing helpers only sign input #0.
      tx = KotlinUtils.kmp2scala(psbt.getGlobal.getTx)
      unsignedTx = anchorTx.updateTx(tx.copy(txIn = anchorTx.txInfo.tx.txIn.head +: tx.txIn))
      totalAmountIn = fundPsbtResponse.amountIn + AnchorOutputsCommitmentFormat.anchorAmount
      adjustedTx = adjustAnchorOutputChange(unsignedTx, commitTx, totalAmountIn, commitFeerate, targetFeerate, dustLimit)
    } yield {
      (adjustedTx, totalAmountIn)
    }
  }

  private def addInputs(htlcTx: HtlcWithWitnessData, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(HtlcWithWitnessData, Satoshi)] = {
    import fr.acinq.bitcoinscala.KotlinUtils._
    val htlcTxWeight = htlcTx.txInfo match {
      case _: HtlcSuccessTx => commitments.commitmentFormat.htlcSuccessWeight
      case _: HtlcTimeoutTx => commitments.commitmentFormat.htlcTimeoutWeight
    }
    // We want the feerate of our final HTLC tx to equal targetFeerate. However, we removed the HTLC input from what we
    // send to fundrawtransaction, so bitcoind will not know the total weight of the final tx. In order to make up for
    // this difference, we need to tell bitcoind to target a higher feerate that takes into account the weight of the
    // input we removed.
    // That feerate will satisfy the following equality:
    // feerate * weight_seen_by_bitcoind = target_feerate * (weight_seen_by_bitcoind + htlc_input_weight)
    // So: feerate = target_feerate * (1 + htlc_input_weight / weight_seen_by_bitcoind)
    // Because bitcoind will add at least one P2WPKH input, weight_seen_by_bitcoind >= htlc_tx_weight + p2wpkh_weight
    // Thus: feerate <= target_feerate * (1 + htlc_input_weight / (htlc_tx_weight + p2wpkh_weight))
    // NB: we don't take into account the fee paid by our HTLC input: we will take it into account when we adjust the
    // change output amount (unless bitcoind didn't add any change output, in that case we will overpay the fee slightly).
    val weightRatio = 1.0 + (htlcInputMaxWeight.toDouble / (htlcTxWeight + claimP2WPKHOutputWeight))
    val Some(address) = computeScriptAddress(nodeParams.chainHash, Script.pay2wpkh(PlaceHolderPubKey))

    // NB: fundpsbt doesn't support non-wallet inputs, so we clear the input and re-add it later.
    bitcoinClient.fundPsbt(Seq(address -> commitments.localParams.dustLimit), htlcTx.txInfo.tx.lockTime, FundPsbtOptions(targetFeerate * weightRatio, lockUtxos = true, changePosition = Some(1))).map(fundPsbtResponse => {
      // We add the HTLC input (from the commit tx) and restore the HTLC output.
      // NB: we can't modify them because they are signed by our peer (with SIGHASH_SINGLE | SIGHASH_ANYONECANPAY).
      val tx: Transaction = fundPsbtResponse.psbt.getGlobal.getTx
      val txWithHtlcInput = tx.copy(
        txIn = htlcTx.txInfo.tx.txIn ++ tx.txIn,
        txOut = htlcTx.txInfo.tx.txOut ++ tx.txOut.tail
      )
      val unsignedTx = htlcTx.updateTx(txWithHtlcInput)
      val totalAmountIn = fundPsbtResponse.amountIn + unsignedTx.txInfo.amountIn
      val adjustedTx = adjustHtlcTxChange(unsignedTx, totalAmountIn, targetFeerate, commitments.localParams.dustLimit, commitments.commitmentFormat)
      (adjustedTx, totalAmountIn)
    })
  }

}
