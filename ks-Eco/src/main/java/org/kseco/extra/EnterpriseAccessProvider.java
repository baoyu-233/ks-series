package org.kseco.extra;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.UUID;

/** Enterprise Extra-owned access API. ks-Eco only forwards web requests to this provider. */
public interface EnterpriseAccessProvider {
    boolean handleAccessRequest(HttpExchange exchange, String path, String query) throws IOException;

    boolean hasPermission(String enterpriseId, UUID playerUuid, String permission);
}
