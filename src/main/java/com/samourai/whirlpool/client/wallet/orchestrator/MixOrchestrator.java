package com.samourai.whirlpool.client.wallet.orchestrator;

import com.samourai.whirlpool.client.mix.listener.MixStep;
import com.samourai.whirlpool.client.mix.listener.MixSuccess;
import com.samourai.whirlpool.client.wallet.WhirlpoolWallet;
import com.samourai.whirlpool.client.wallet.beans.MixOrchestratorState;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolAccount;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxoPriorityComparator;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxoStatus;
import com.samourai.whirlpool.client.whirlpool.listener.WhirlpoolClientListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java8.util.function.Function;
import java8.util.function.Predicate;
import java8.util.stream.Collectors;
import java8.util.stream.Stream;
import java8.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MixOrchestrator extends AbstractOrchestrator {
  private final Logger log = LoggerFactory.getLogger(MixOrchestrator.class);
  private WhirlpoolWallet whirlpoolWallet;
  private int maxClients;
  private int clientDelay;

  private Map<String, Mixing> mixing;

  public MixOrchestrator(
      int loopDelay, WhirlpoolWallet whirlpoolWallet, int maxClients, int clientDelay) {
    super(loopDelay);
    this.whirlpoolWallet = whirlpoolWallet;
    this.maxClients = maxClients;
    this.clientDelay = clientDelay;
  }

  @Override
  protected void resetOrchestrator() {
    super.resetOrchestrator();
    this.mixing = new HashMap<String, Mixing>();
  }

  @Override
  protected void runOrchestrator() {
    // check idles
    try {
      while (computeNbIdle() > 0) {

        // sleep clientDelay
        boolean waited = waitForLastRunDelay(clientDelay, "Sleeping for clientDelay");
        if (waited) {
          // re-check for idle on wakeup
          if (computeNbIdle() == 0) {
            return;
          }
        }

        // idles detected
        if (log.isDebugEnabled()) {
          log.debug(
              getState().getNbMixing()
                  + "/"
                  + maxClients
                  + " threads running => checking for queued utxos to mix...");
        }

        // find & mix
        boolean startedNewMix = findQueuedAndMix();
        if (!startedNewMix) {
          // nothing more to mix => exit this loop
          return;
        }
      }

      // all threads running
      if (log.isDebugEnabled()) {
        log.debug(
            getState().getNbMixing() + "/" + maxClients + " threads running: all threads running.");
      }
    } catch (Exception e) {
      log.error("", e);
    }
  }

  public boolean findQueuedAndMix() throws Exception {
    // start mixing up to nbIdle utxos
    Collection<WhirlpoolUtxo> whirlpoolUtxos = findToMixByPriority(1);
    if (whirlpoolUtxos.isEmpty()) {
      if (log.isDebugEnabled()) {
        log.debug("No queued utxo mixable now.");
      }
      return false;
    }

    WhirlpoolUtxo whirlpoolUtxo = whirlpoolUtxos.iterator().next();
    if (log.isDebugEnabled()) {
      log.debug("Found queued utxo to mix => mix now");
    }

    // start mix
    setLastRun();
    mix(whirlpoolUtxo);
    return true;
  }

  public MixOrchestratorState getState() {
    List<WhirlpoolUtxo> utxosMixing =
        StreamSupport.stream(mixing.values())
            .map(
                new Function<Mixing, WhirlpoolUtxo>() {
                  @Override
                  public WhirlpoolUtxo apply(Mixing m) {
                    return m.getUtxo();
                  }
                })
            .collect(Collectors.<WhirlpoolUtxo>toList());
    int nbIdle = computeNbIdle();
    int nbQueued = (int) findToMix().count();
    return new MixOrchestratorState(utxosMixing, maxClients, nbIdle, nbQueued);
  }

  private int computeNbIdle() {
    int nbIdle = maxClients - mixing.size();
    return nbIdle;
  }

  protected List<WhirlpoolUtxo> findToMixByPriority(int nbUtxos) {
    List<WhirlpoolUtxo> results = new ArrayList<WhirlpoolUtxo>();

    // exclude hashs of utxos currently mixing
    Set<String> excludedHashs = new HashSet<String>();
    for (Mixing m : mixing.values()) {
      excludedHashs.add(m.getUtxo().getUtxo().tx_hash);
    }

    while (results.size() < nbUtxos) {
      WhirlpoolUtxo whirlpoolUtxo = findToMixByPriority(excludedHashs);
      if (whirlpoolUtxo == null) {
        // no more utxos eligible yet
        return results;
      }

      // eligible utxo found
      results.add(whirlpoolUtxo);
      excludedHashs.add(whirlpoolUtxo.getUtxo().tx_hash);
    }
    return results;
  }

  protected Stream<WhirlpoolUtxo> findToMix() {
    try {
      return StreamSupport.stream(
              whirlpoolWallet.getUtxos(false, WhirlpoolAccount.PREMIX, WhirlpoolAccount.POSTMIX))
          .filter(
              new Predicate<WhirlpoolUtxo>() {
                @Override
                public boolean test(WhirlpoolUtxo whirlpoolUtxo) {
                  return WhirlpoolUtxoStatus.MIX_QUEUE.equals(whirlpoolUtxo.getStatus());
                }
              });
    } catch (Exception e) {
      return StreamSupport.stream(new ArrayList());
    }
  }

  protected WhirlpoolUtxo findToMixByPriority(final Set<String> excludedHashs) {
    return findToMix()
        // exclude hashs
        .filter(
            new Predicate<WhirlpoolUtxo>() {
              @Override
              public boolean test(WhirlpoolUtxo whirlpoolUtxo) {
                return !excludedHashs.contains(whirlpoolUtxo.getUtxo().tx_hash);
              }
            })
        .filter(
            new Predicate<WhirlpoolUtxo>() {
              @Override
              public boolean test(WhirlpoolUtxo whirlpoolUtxo) {
                return whirlpoolUtxo.getUtxo().confirmations
                    >= WhirlpoolWallet.MIX_MIN_CONFIRMATIONS;
              }
            })
        .sorted(new WhirlpoolUtxoPriorityComparator())
        .findFirst()
        .orElse(null);
  }

  public void mixQueue(WhirlpoolUtxo whirlpoolUtxo) {
    if (whirlpoolUtxo.getPool() == null) {
      log.warn("mixQueue ignored: no pool set for " + whirlpoolUtxo);
      return;
    }
    final String key = whirlpoolUtxo.getUtxo().toKey();
    if (!mixing.containsKey(key)) {
      // add to queue
      whirlpoolUtxo.setStatus(WhirlpoolUtxoStatus.MIX_QUEUE);
      if (log.isDebugEnabled()) {
        log.debug(" + mixQueue: " + whirlpoolUtxo.toString());
      }
      notifyOrchestrator();
    } else {
      log.warn("mixQueue ignored: utxo already queued or mixing: " + whirlpoolUtxo);
    }
  }

  private void mix(final WhirlpoolUtxo whirlpoolUtxo) throws Exception {
    final String key = whirlpoolUtxo.getUtxo().toKey();
    WhirlpoolClientListener utxoListener =
        new WhirlpoolClientListener() {
          @Override
          public void success(int nbMixs, MixSuccess mixSuccess) {
            whirlpoolWallet.clearCache(whirlpoolUtxo.getAccount());
            whirlpoolWallet.clearCache(WhirlpoolAccount.POSTMIX);
          }

          @Override
          public void fail(int currentMix, int nbMixs) {
            mixing.remove(key);

            // idle => notify orchestrator
            notifyOrchestrator();
          }

          @Override
          public void progress(
              int currentMix,
              int nbMixs,
              MixStep step,
              String stepInfo,
              int stepNumber,
              int nbSteps) {}

          @Override
          public void mixSuccess(int currentMix, int nbMixs, MixSuccess mixSuccess) {
            whirlpoolWallet.clearCache(whirlpoolUtxo.getAccount());
            whirlpoolWallet.clearCache(WhirlpoolAccount.POSTMIX);
            mixing.remove(key);

            // idle => notify orchestrator
            notifyOrchestrator();
          }
        };

    // start mix
    WhirlpoolClientListener listener = whirlpoolWallet.mix(whirlpoolUtxo, utxoListener);

    mixing.put(key, new Mixing(whirlpoolUtxo, listener));
  }

  public void onUtxoDetected(WhirlpoolUtxo whirlpoolUtxo) {
    // enqueue unfinished POSTMIX utxos
    if (WhirlpoolAccount.POSTMIX.equals(whirlpoolUtxo.getAccount())
        && WhirlpoolUtxoStatus.READY.equals(whirlpoolUtxo.getStatus())
        && whirlpoolUtxo.getMixsTarget() < whirlpoolUtxo.getMixsDone()
        && whirlpoolUtxo.getPool() != null) {

      log.info(" o Mix: new POSTMIX utxo detected, adding to mixQueue: " + whirlpoolUtxo);
      mixQueue(whirlpoolUtxo);
    }
  }

  public void onUtxoConfirmed(WhirlpoolUtxo whirlpoolUtxo) {
    // wakeup on confirmed PREMIX/POSTMIX
    if (WhirlpoolUtxoStatus.MIX_QUEUE.equals(whirlpoolUtxo.getStatus())
        && whirlpoolUtxo.getUtxo().confirmations >= WhirlpoolWallet.MIX_MIN_CONFIRMATIONS) {
      log.info(" o Mix: new CONFIRMED utxo detected, checking for mix: " + whirlpoolUtxo);
      notifyOrchestrator();
    }
  }

  private static class Mixing {
    private WhirlpoolUtxo utxo;
    private WhirlpoolClientListener listener;

    public Mixing(WhirlpoolUtxo utxo, WhirlpoolClientListener listener) {
      this.utxo = utxo;
      this.listener = listener;
    }

    public WhirlpoolUtxo getUtxo() {
      return utxo;
    }

    public WhirlpoolClientListener getListener() {
      return listener;
    }
  }
}