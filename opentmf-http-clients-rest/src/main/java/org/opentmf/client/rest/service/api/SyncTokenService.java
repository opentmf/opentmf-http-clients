package org.opentmf.client.rest.service.api;

public interface SyncTokenService {

  String getTokenType();

  String getToken();

  String getToken(String additionalScopes);
}
