package org.obsidiantoaster.generator.websocket;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnMessage;
import javax.websocket.Session;

/**
 * Client for the catapult websocket endpoint.
 */
@ClientEndpoint
public class StatusEndpointClient {
    private final Session session;

    public StatusEndpointClient(Session clientSession) {
        this.session = clientSession;
    }

    @OnMessage
    public void onMessage(String message) {
        session.getAsyncRemote().sendText(message);
    }
}
