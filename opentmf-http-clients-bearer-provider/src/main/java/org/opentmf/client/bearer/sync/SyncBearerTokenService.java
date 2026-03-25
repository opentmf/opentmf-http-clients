package org.opentmf.client.bearer.sync;

import org.opentmf.client.rest.service.api.SyncTokenService;

public interface SyncBearerTokenService extends SyncTokenService {

  void clearCache();
}
