package com.samourai.whirlpool.client.wallet.beans;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;

public enum WhirlpoolServer {
  TEST(
      "pool.whirl.mx:8081",
      TestNet3Params.get(),
      true,
      "vpub5YS8pQgZKVbrSn9wtrmydDWmWMjHrxL2mBCZ81BDp7Z2QyCgTLZCrnBprufuoUJaQu1ZeiRvUkvdQTNqV6hS96WbbVZgweFxYR1RXYkBcKt",
      10000),
  MAIN("TODO", MainNetParams.get(), true, "TODO", 12345), // TODO
  LOCAL_TEST(
      "127.0.0.1:8080",
      TestNet3Params.get(),
      false,
      "vpub5YS8pQgZKVbrSn9wtrmydDWmWMjHrxL2mBCZ81BDp7Z2QyCgTLZCrnBprufuoUJaQu1ZeiRvUkvdQTNqV6hS96WbbVZgweFxYR1RXYkBcKt",
      10000);

  private String serverUrl;
  private NetworkParameters params;
  private boolean ssl;
  private String feeXpub;
  private long feeValue;

  WhirlpoolServer(
      String serverUrl, NetworkParameters params, boolean ssl, String feeXpub, long feeValue) {
    this.serverUrl = serverUrl;
    this.params = params;
    this.ssl = ssl;
    this.feeXpub = feeXpub;
    this.feeValue = feeValue;
  }

  public String getServerUrl() {
    return serverUrl;
  }

  public NetworkParameters getParams() {
    return params;
  }

  public boolean isSsl() {
    return ssl;
  }

  public String getFeeXpub() {
    return feeXpub;
  }

  public long getFeeValue() {
    return feeValue;
  }
}
