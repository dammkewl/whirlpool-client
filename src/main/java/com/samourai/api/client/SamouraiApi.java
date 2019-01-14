package com.samourai.api.client;

import com.samourai.api.client.beans.MultiAddrResponse;
import com.samourai.api.client.beans.UnspentResponse;
import com.samourai.http.client.IHttpClient;
import com.samourai.whirlpool.client.utils.PushTxService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bitcoinj.core.Transaction;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SamouraiApi implements PushTxService {
  private Logger log = LoggerFactory.getLogger(SamouraiApi.class);

  private static final String URL_BACKEND = "https://api.samouraiwallet.com/test";
  private static final String URL_UNSPENT = "/v2/unspent?active=";
  private static final String URL_MULTIADDR = "/v2/multiaddr?active=";
  private static final String URL_INIT_BIP84 = "/v2/xpub";
  private static final String URL_FEES = "/v2/fees";
  private static final String URL_PUSHTX = "/v2/pushtx";
  private static final int MAX_FEE_PER_BYTE = 500;
  private static final int FAILOVER_FEE_PER_BYTE = 400;
  private static final int SLEEP_REFRESH_UTXOS = 15000;

  private IHttpClient httpClient;

  public SamouraiApi(IHttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public List<UnspentResponse.UnspentOutput> fetchUtxos(String zpub) throws Exception {
    String url = URL_BACKEND + URL_UNSPENT + zpub;
    if (log.isDebugEnabled()) {
      log.debug("fetchUtxos: " + url);
    }
    UnspentResponse unspentResponse = httpClient.parseJson(url, UnspentResponse.class);
    List<UnspentResponse.UnspentOutput> unspentOutputs =
        new ArrayList<UnspentResponse.UnspentOutput>();
    if (unspentResponse.unspent_outputs != null) {
      unspentOutputs = Arrays.asList(unspentResponse.unspent_outputs);
    }
    return unspentOutputs;
  }

  public List<MultiAddrResponse.Address> fetchAddresses(String zpub) throws Exception {
    String url = URL_BACKEND + URL_MULTIADDR + zpub;
    if (log.isDebugEnabled()) {
      log.debug("fetchAddress: " + url);
    }
    MultiAddrResponse multiAddrResponse = httpClient.parseJson(url, MultiAddrResponse.class);
    List<MultiAddrResponse.Address> addresses = new ArrayList<MultiAddrResponse.Address>();
    if (multiAddrResponse.addresses != null) {
      addresses = Arrays.asList(multiAddrResponse.addresses);
    }
    return addresses;
  }

  public MultiAddrResponse.Address fetchAddress(String zpub) throws Exception {
    List<MultiAddrResponse.Address> addresses = fetchAddresses(zpub);
    if (addresses.size() != 1) {
      throw new Exception("Address count=" + addresses.size());
    }
    MultiAddrResponse.Address address = addresses.get(0);

    if (log.isDebugEnabled()) {
      log.debug(
          "fetchAddress "
              + zpub
              + ": account_index="
              + address.account_index
              + ", change_index="
              + address.change_index);
    }
    return address;
  }

  public void initBip84(String zpub) throws Exception {
    String url = URL_BACKEND + URL_INIT_BIP84;
    if (log.isDebugEnabled()) {
      log.debug("initBip84: zpub=" + zpub);
    }
    Map<String, String> postBody = new HashMap<String, String>();
    postBody.put("xpub", zpub);
    postBody.put("type", "new");
    postBody.put("segwit", "bip84");
    httpClient.postUrlEncoded(url, postBody);
  }

  public int fetchFees() {
    return fetchFees(true);
  }

  private int fetchFees(boolean retry) {
    String url = URL_BACKEND + URL_FEES;
    int fees2 = 0;
    try {
      Map feesResponse = httpClient.parseJson(url, Map.class);
      fees2 = Integer.parseInt(feesResponse.get("2").toString());
    } catch (Exception e) {
      log.error("Invalid fee response from server", e);
    }
    if (fees2 < 1) {
      if (retry) {
        return fetchFees(false);
      }
      return FAILOVER_FEE_PER_BYTE;
    }
    return Math.min(fees2, MAX_FEE_PER_BYTE);
  }

  public void refreshUtxos() throws Exception {
    Thread.sleep(SamouraiApi.SLEEP_REFRESH_UTXOS);
  }

  @Override
  public void pushTx(String txHex) throws Exception {
    String url = URL_BACKEND + URL_PUSHTX;
    Map<String, String> postBody = new HashMap<String, String>();
    postBody.put("tx", txHex);
    httpClient.postUrlEncoded(url, postBody);
  }

  @Override
  public void pushTx(Transaction tx) throws Exception {
    String txHex = new String(Hex.encode(tx.bitcoinSerialize()));
    pushTx(txHex);
  }
}
