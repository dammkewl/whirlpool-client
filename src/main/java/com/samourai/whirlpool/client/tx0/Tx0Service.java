package com.samourai.whirlpool.client.tx0;

import com.samourai.wallet.api.backend.beans.HttpException;
import com.samourai.wallet.api.backend.beans.UnspentResponse;
import com.samourai.wallet.bip69.BIP69OutputComparator;
import com.samourai.wallet.client.Bip84Wallet;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric;
import com.samourai.wallet.util.FeeUtil;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.client.wallet.WhirlpoolWalletConfig;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.samourai.whirlpool.client.whirlpool.beans.Tx0Data;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.protocol.beans.Utxo;
import com.samourai.whirlpool.protocol.fee.WhirlpoolFee;
import com.samourai.whirlpool.protocol.rest.Tx0DataResponse;
import java.util.*;
import java8.util.function.ToLongFunction;
import java8.util.stream.StreamSupport;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Tx0Service {
  private Logger log = LoggerFactory.getLogger(Tx0Service.class);
  protected static final int NB_PREMIX_MAX = 600;

  private final Bech32UtilGeneric bech32Util = Bech32UtilGeneric.getInstance();
  private final WhirlpoolFee whirlpoolFee = WhirlpoolFee.getInstance();
  private final FeeUtil feeUtil = FeeUtil.getInstance();

  private WhirlpoolWalletConfig config;

  public Tx0Service(WhirlpoolWalletConfig config) {
    this.config = config;
  }

  private long computePremixValue(Pool pool, int feePremix) {
    // compute minerFeePerMustmix
    long txPremixFeesEstimate =
        feeUtil.estimatedFeeSegwit(
            0, 0, pool.getMixAnonymitySet(), pool.getMixAnonymitySet(), 0, feePremix);
    long minerFeePerMustmix = txPremixFeesEstimate / pool.getMinMustMix();
    // pool.getMixAnonymitySet();
    long premixValue = pool.getDenomination() + minerFeePerMustmix;

    // make sure destinationValue is acceptable for pool
    long premixBalanceMin = pool.computePremixBalanceMin(false);
    long premixBalanceCap = pool.computePremixBalanceCap(false);
    long premixBalanceMax = pool.computePremixBalanceMax(false);

    long premixValueFinal = premixValue;
    premixValueFinal = Math.min(premixValueFinal, premixBalanceMax);
    premixValueFinal = Math.min(premixValueFinal, premixBalanceCap);
    premixValueFinal = Math.max(premixValueFinal, premixBalanceMin);

    if (log.isDebugEnabled()) {
      log.debug(
          "premixValueFinal="
              + premixValueFinal
              + ", premixValue="
              + premixValue
              + ", minerFeePerMustmix="
              + minerFeePerMustmix
              + ", txPremixFeesEstimate="
              + txPremixFeesEstimate
              + " for poolId="
              + pool.getPoolId());
    }
    return premixValueFinal;
  }

  private int computeNbPremixMax(
      long premixValue,
      Collection<UnspentResponse.UnspentOutput> depositSpendFroms,
      long samouraiFee,
      int feeTx0) {
    long spendFromBalance = computeSpendFromBalance(depositSpendFroms);

    // compute nbPremix ignoring TX0 fee
    int nbPremixInitial = (int) Math.ceil(spendFromBalance / premixValue);

    // compute nbPremix with TX0 fee
    int nbPremix = nbPremixInitial;
    while (true) {
      // estimate TX0 fee for nbPremix
      long tx0MinerFee = computeTx0MinerFee(nbPremix, feeTx0, depositSpendFroms);
      long spendValue = computeTx0SpendValue(premixValue, nbPremix, samouraiFee, tx0MinerFee);
      if (log.isDebugEnabled()) {
        log.debug(
            "computeNbPremixMax: nbPremix="
                + nbPremix
                + " => spendValue="
                + spendValue
                + ", tx0MinerFee="
                + tx0MinerFee
                + ", spendFromBalance="
                + spendFromBalance
                + ", nbPremixInitial="
                + nbPremixInitial);
      }
      if (spendFromBalance < spendValue) {
        // if UTXO balance is insufficient, try with less nbPremix
        nbPremix--;
      } else {
        // nbPremix found
        break;
      }
    }
    // no negative value
    if (nbPremix < 0) {
      nbPremix = 0;
    }
    return nbPremix;
  }

  protected long computeTx0MinerFee(
      int nbPremix, long feeTx0, Collection<UnspentResponse.UnspentOutput> spendFroms) {
    int nbOutputsNonOpReturn = nbPremix + 2; // outputs + change + fee

    // spendFroms can be NULL (for fee simulation)
    int nbSpendFroms = (spendFroms != null ? spendFroms.size() : 1);

    // spend from N bech32 input
    long tx0MinerFee =
        feeUtil.estimatedFeeSegwit(0, 0, nbSpendFroms, nbOutputsNonOpReturn, 1, feeTx0);

    if (log.isDebugEnabled()) {
      log.debug(
          "tx0 minerFee: "
              + tx0MinerFee
              + "sats, totalBytes="
              + "b for nbPremix="
              + nbPremix
              + ", feeTx0="
              + feeTx0);
    }
    return tx0MinerFee;
  }

  private long computeTx0SpendValue(
      long premixValue, int nbPremix, long samouraiFee, long tx0MinerFee) {
    long changeValue = (premixValue * nbPremix) + samouraiFee + tx0MinerFee;
    return changeValue;
  }

  public long computeSpendFromBalanceMin(Pool pool, int feeTx0, int feePremix, int nbPremix) {
    long premixValue = computePremixValue(pool, feePremix);
    long tx0MinerFee = computeTx0MinerFee(nbPremix, feeTx0, null);
    long samouraiFee = pool.getFeeValue();
    long spendValue = computeTx0SpendValue(premixValue, nbPremix, samouraiFee, tx0MinerFee);
    return spendValue;
  }

  /** Generate maxOutputs premixes outputs max. */
  public Tx0 tx0(
          Collection<byte[]> spendFromPrivKeys,
      Collection<UnspentResponse.UnspentOutput> depositSpendFroms,
      Bip84Wallet depositWallet,
      Bip84Wallet premixWallet,
      int feeTx0,
      int feePremix,
      Pool pool,
      Integer maxOutputs)
      throws Exception {

    // fetch fresh Tx0Data
    Tx0Data tx0Data = fetchTx0Data(pool.getPoolId());

    return tx0(
        spendFromPrivKeys,
        depositSpendFroms,
        depositWallet,
        premixWallet,
        feeTx0,
        feePremix,
        pool,
        maxOutputs,
        tx0Data);
  }

  public Tx0 tx0(
          Collection<byte[]> spendFromPrivKeys,
      Collection<UnspentResponse.UnspentOutput> depositSpendFroms,
      Bip84Wallet depositWallet,
      Bip84Wallet premixWallet,
      int feeTx0,
      int feePremix,
      Pool pool,
      Integer maxOutputs,
      Tx0Data tx0Data)
      throws Exception {

    log.info(
        " • Tx0: spendFrom="
            + depositSpendFroms
            + ", feeTx0="
            + feeTx0
            + ", feePremix="
            + feePremix
            + ", poolId="
            + pool.getPoolId()
            + ", maxOutputs="
            + (maxOutputs != null ? maxOutputs : "*")
            + ", tx0Data=["
            + tx0Data
            + "]");

    // check balance min
    final long spendFromBalanceMin =
            config.getTx0Service().computeSpendFromBalanceMin(pool, feeTx0, feePremix, 1);

    long spendFromBalance = computeSpendFromBalance(depositSpendFroms);
    if (spendFromBalance < spendFromBalanceMin) {
      throw new NotifiableException(
              "Insufficient utxo value for Tx0: " + spendFromBalance + " < " + spendFromBalanceMin);
    }

    // compute premixValue for pool
    long premixValue = computePremixValue(pool, feePremix);

    return tx0(
        spendFromPrivKeys,
        depositSpendFroms,
        depositWallet,
        premixWallet,
        feeTx0,
        maxOutputs,
        premixValue,
        tx0Data);
  }

  protected Tx0 tx0(
          Collection<byte[]> spendFromPrivKeys,
      Collection<UnspentResponse.UnspentOutput> depositSpendFroms,
      Bip84Wallet depositWallet,
      Bip84Wallet premixWallet,
      int feeTx0,
      Integer maxOutputs,
      long premixValue,
      Tx0Data tx0Data)
      throws Exception {
    NetworkParameters params = config.getNetworkParameters();

    if (spendFromPrivKeys.size() != depositSpendFroms.size()) {
      throw new IllegalArgumentException("spendFromPrivKeys count vs depositSpendFroms count mismatch");
    }

    // compute opReturnValue for feePaymentCode and feePayload
    byte[] feePayload = tx0Data.getFeePayload();
    int feeIndice;
    String feeAddressBech32;
    long samouraiFee;
    if (tx0Data.getFeeValue() > 0) {
      // pay to fee
      feeIndice = tx0Data.getFeeIndice();
      feeAddressBech32 = tx0Data.getFeeAddress();
      samouraiFee = tx0Data.getFeeValue();
      if (log.isDebugEnabled()) {
        log.debug(
            "feeAddressDestination: samourai => feeAddress="
                + feeAddressBech32
                + ", feeIndice="
                + feeIndice
                + ", samouraiFee="
                + samouraiFee);
      }
    } else {
      // pay to deposit
      feeIndice = 0;
      feeAddressBech32 = bech32Util.toBech32(depositWallet.getNextChangeAddress(), params);
      samouraiFee = tx0Data.getFeeChange();
      if (log.isDebugEnabled()) {
        log.debug(
            "feeAddressDestination: deposit => feeAddress="
                + feeAddressBech32
                + ", samouraiFee="
                + samouraiFee);
      }
    }
    String feePaymentCode = tx0Data.getFeePaymentCode();
    byte[] opReturnValue =
        whirlpoolFee.encode(
            feeIndice,
            feePayload,
            feePaymentCode,
            params,
            spendFromPrivKeys.iterator().next(),
            depositSpendFroms.iterator().next().computeOutpoint(params));
    if (log.isDebugEnabled()) {
      log.debug(
          "computing opReturnValue for feeIndice="
              + feeIndice
              + ", feePayloadHex="
              + (feePayload != null ? Hex.toHexString(feePayload) : "null"));
    }
    return tx0(
        spendFromPrivKeys,
        depositSpendFroms,
        depositWallet,
        premixWallet,
        feeTx0,
        maxOutputs,
        premixValue,
        samouraiFee,
        opReturnValue,
        feeAddressBech32);
  }

  protected Tx0 tx0(
          Collection<byte[]> spendFromPrivKeys,
      Collection<UnspentResponse.UnspentOutput> depositSpendFroms,
      Bip84Wallet depositWallet,
      Bip84Wallet premixWallet,
      int feeTx0,
      Integer maxOutputs,
      long premixValue,
      long samouraiFee,
      byte[] opReturnValue,
      String feeAddressBech32)
      throws Exception {

    if (depositSpendFroms.size() <= 0) {
      throw new IllegalArgumentException("depositSpendFroms should be > 0");
    }

    if (samouraiFee <= 0) {
      throw new IllegalArgumentException("samouraiFee should be > 0");
    }

    // compute nbPremix
    int nbPremix =
        computeNbPremixMax(
            premixValue,
            depositSpendFroms,
            samouraiFee,
            feeTx0); // cap with balance and tx0 minerFee
    if (maxOutputs != null) {
      nbPremix = Math.min(maxOutputs, nbPremix); // cap with maxOutputs
    }
    nbPremix = Math.min(NB_PREMIX_MAX, nbPremix); // cap with UTXO NB_PREMIX_MAX

    long spendFromBalance = computeSpendFromBalance(depositSpendFroms);

    // at least 1 nbPremix
    if (nbPremix < 1) {
      throw new Exception(
          "Invalid nbPremix detected, please report this bug. nbPremix="
              + nbPremix
              + " for spendFromBalance="
              + spendFromBalance
              + ", feeTx0="
              + feeTx0
              + ", premixValue="
              + premixValue);
    }

    // fee selection
    long tx0MinerFee = computeTx0MinerFee(nbPremix, feeTx0, depositSpendFroms);

    long spendValue = computeTx0SpendValue(premixValue, nbPremix, samouraiFee, tx0MinerFee);
    long changeValue = spendFromBalance - spendValue;

    //
    // tx0
    //

    Transaction tx =
        buildTx0(
            spendFromPrivKeys,
            depositSpendFroms,
            depositWallet,
            premixWallet,
            premixValue,
            samouraiFee,
            opReturnValue,
            feeAddressBech32,
            config.getNetworkParameters(),
            nbPremix,
            tx0MinerFee,
            changeValue);

    final String hexTx = new String(Hex.encode(tx.bitcoinSerialize()));
    final String strTxHash = tx.getHashAsString();

    tx.verify();
    // System.out.println(tx);
    if (log.isDebugEnabled()) {
      log.debug("Tx0 hash: " + strTxHash);
      log.debug("Tx0 hex: " + hexTx);
      long feePrice = tx0MinerFee / tx.getVirtualTransactionSize();
      log.debug("Tx0 size: " + tx.getVirtualTransactionSize() + "b, feePrice=" + feePrice + "s/b");
    }

    List<Utxo> premixUtxos = new ArrayList<Utxo>();
    for (TransactionOutput to : tx.getOutputs()) {
      Utxo utxo = new Utxo(strTxHash, to.getIndex());
      premixUtxos.add(utxo);
    }
    return new Tx0(tx, premixUtxos);
  }

  protected long computeSpendFromBalance(Collection<UnspentResponse.UnspentOutput> spendFroms) {
    long balance =
        StreamSupport.stream(spendFroms)
            .mapToLong(
                new ToLongFunction<UnspentResponse.UnspentOutput>() {
                  @Override
                  public long applyAsLong(UnspentResponse.UnspentOutput transactionOutPoint) {
                    return transactionOutPoint.value;
                  }
                })
            .sum();
    return balance;
  }

  protected Transaction buildTx0(
      Collection<byte[]> spendFromPrivKeys,
      Collection<UnspentResponse.UnspentOutput> depositSpendFroms,
      Bip84Wallet depositWallet,
      Bip84Wallet premixWallet,
      long premixValue,
      long samouraiFee,
      byte[] opReturnValue,
      String feeAddressBech32,
      NetworkParameters params,
      int nbPremix,
      long tx0MinerFee,
      long changeValue)
      throws Exception {

    //
    // tx0
    //

    //
    // make tx:
    // 5 spendTo outputs
    // SW fee
    // change
    // OP_RETURN
    //
    List<TransactionOutput> outputs = new ArrayList<TransactionOutput>();
    Transaction tx = new Transaction(params);

    //
    // premix outputs
    //
    for (int j = 0; j < nbPremix; j++) {
      // send to PREMIX
      HD_Address toAddress = premixWallet.getNextAddress();
      String toAddressBech32 = bech32Util.toBech32(toAddress, params);
      if (log.isDebugEnabled()) {
        log.debug(
            "Tx0 out (premix): address="
                + toAddressBech32
                + ", path="
                + toAddress.toJSON().get("path")
                + " ("
                + premixValue
                + " sats)");
      }

      TransactionOutput txOutSpend =
          bech32Util.getTransactionOutput(toAddressBech32, premixValue, params);
      outputs.add(txOutSpend);
    }

    if (changeValue > 0) {
      //
      // 1 change output
      //
      HD_Address changeAddress = depositWallet.getNextChangeAddress();
      String changeAddressBech32 = bech32Util.toBech32(changeAddress, params);
      TransactionOutput txChange =
          bech32Util.getTransactionOutput(changeAddressBech32, changeValue, params);
      outputs.add(txChange);
      if (log.isDebugEnabled()) {
        log.debug(
            "Tx0 out (change): address="
                + changeAddressBech32
                + ", path="
                + changeAddress.toJSON().get("path")
                + " ("
                + changeValue
                + " sats)");
      }
    } else {
      if (log.isDebugEnabled()) {
        log.debug("Tx0: spending whole utx0, no change");
      }
      if (changeValue < 0) {
        throw new Exception(
            "Negative change detected, please report this bug. changeValue="
                + changeValue
                + ", tx0MinerFee="
                + tx0MinerFee);
      }
    }

    // samourai fee
    TransactionOutput txSWFee =
        bech32Util.getTransactionOutput(feeAddressBech32, samouraiFee, params);
    outputs.add(txSWFee);
    if (log.isDebugEnabled()) {
      log.debug("Tx0 out (fee): feeAddress=" + feeAddressBech32 + " (" + samouraiFee + " sats)");
    }

    // add OP_RETURN output
    Script op_returnOutputScript =
        new ScriptBuilder().op(ScriptOpCodes.OP_RETURN).data(opReturnValue).build();
    TransactionOutput txFeeOutput =
        new TransactionOutput(params, null, Coin.valueOf(0L), op_returnOutputScript.getProgram());
    outputs.add(txFeeOutput);
    if (log.isDebugEnabled()) {
      log.debug("Tx0 out (OP_RETURN): " + opReturnValue.length + " bytes");
    }
    if (opReturnValue.length != WhirlpoolFee.FEE_LENGTH) {
      throw new Exception(
          "Invalid opReturnValue length detected, please report this bug. opReturnValue="
              + opReturnValue
              + " vs "
              + WhirlpoolFee.FEE_LENGTH);
    }

    // all outputs
    Collections.sort(outputs, new BIP69OutputComparator());
    for (TransactionOutput to : outputs) {
      tx.addOutput(to);
    }

    // input
    // TODO handle multiple depositSpendFroms & spendFromPrivKeys
    ECKey spendFromKey = ECKey.fromPrivate(spendFromPrivKeys.iterator().next());
    TransactionOutPoint depositSpendFrom =
        depositSpendFroms.iterator().next().computeOutpoint(params);
    final Script segwitPubkeyScript = ScriptBuilder.createP2WPKHOutputScript(spendFromKey);
    tx.addSignedInput(depositSpendFrom, segwitPubkeyScript, spendFromKey);
    if (log.isDebugEnabled()) {
      log.debug(
          "Tx0 in: utxo="
              + depositSpendFrom
              + " ("
              + depositSpendFrom.getValue().getValue()
              + " sats)");
      log.debug("Tx0 fee: " + tx0MinerFee + " sats");
    }
    tx.verify();
    return tx;
  }

  public Collection<Pool> findPools(
      int nbOutputsMin,
      Collection<Pool> poolsByPreference,
      long utxoValue,
      int feeTx0,
      int feePremix) {
    List<Pool> eligiblePools = new LinkedList<Pool>();
    for (Pool pool : poolsByPreference) {
      boolean eligible = isTx0Possible(utxoValue, pool, feeTx0, feePremix, nbOutputsMin);
      if (eligible) {
        eligiblePools.add(pool);
      }
    }
    return eligiblePools;
  }

  public boolean isTx0Possible(
      long utxoValue, Pool pool, int feeTx0, int feePremix, int nbOutputsMin) {
    long balanceMin = computeSpendFromBalanceMin(pool, feeTx0, feePremix, nbOutputsMin);
    if (log.isDebugEnabled()) {
      log.debug(
          "isTx0Possible["
              + pool.getPoolId()
              + "] spendFromBalanceMin="
              + balanceMin
              + " for nbOutputsMin="
              + nbOutputsMin
              + ", utxoValue="
              + utxoValue
              + ", feeTx0="
              + feeTx0
              + ", feePremix="
              + feePremix);
    }
    return (utxoValue >= balanceMin);
  }

  private Tx0Data fetchTx0Data(String poolId) throws HttpException, NotifiableException {
    String url = WhirlpoolProtocol.getUrlTx0Data(config.getServer(), poolId, config.getScode());
    try {
      Tx0DataResponse tx0Response =
          config.getHttpClient().getJson(url, Tx0DataResponse.class, null);
      byte[] feePayload = WhirlpoolProtocol.decodeBytes(tx0Response.feePayload64);
      Tx0Data tx0Data =
          new Tx0Data(
              tx0Response.feePaymentCode,
              tx0Response.feeValue,
              tx0Response.feeChange,
              // tx0Response.message,
              feePayload,
              tx0Response.feeAddress,
              tx0Response.feeIndice);
      return tx0Data;
    } catch (HttpException e) {
      String restErrorResponseMessage = ClientUtils.parseRestErrorMessage(e);
      if (restErrorResponseMessage != null) {
        throw new NotifiableException(restErrorResponseMessage);
      }
      throw e;
    }
  }
}
